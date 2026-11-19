package com.enterprise.mm.integration.faktura;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.service.IntegrationSettingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Read-only Faktura transport. Tokens and upstream response bodies never leave this class. */
@Component
public class FakturaClient {
    private static final String AUTH_URL = "https://account.faktura.uz/token";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Tashkent");
    private final IntegrationSettingService settings;
    private final CompanyRepository companies;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final Map<UUID, Token> tokens = new ConcurrentHashMap<>();

    public FakturaClient(IntegrationSettingService settings, CompanyRepository companies, ObjectMapper json) {
        this.settings = settings;
        this.companies = companies;
        this.json = json;
    }

    public String verifiedCompanyInn() {
        return session().companyInn();
    }

    public JsonNode documents(LocalDate from, LocalDate to, int type, int skip, int limit, boolean updatedWindow) {
        if (from == null || to == null || to.isBefore(from) || type != 20 && type != 28 && type != 29
            || skip < 0 || limit < 1 || limit > 100) {
            throw new BusinessException("Invalid Faktura document query");
        }
        Session session = session();
        long start = from.atStartOfDay(BUSINESS_ZONE).toInstant().toEpochMilli();
        long end = to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant().toEpochMilli() - 1;
        String timeField = updatedWindow ? "Updated" : "Created";
        String query = "?CompanyInn=" + enc(session.companyInn()) + "&" + timeField + "From=" + start
            + "&" + timeField + "To=" + end + "&types=" + type + "&limit=" + limit + "&skip=" + skip;
        return authorized(session, "GET", api(session.endpoint(), "/Api/Document/GetDocuments") + query, null);
    }

    public JsonNode contents(List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 50 || ids.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new BusinessException("Invalid Faktura content batch");
        }
        Session session = session();
        try {
            String body = json.writeValueAsString(Map.of("DocumentUniqueIds", ids));
            return authorized(session, "POST", api(session.endpoint(), "/Api/Document/GetDocumentsContent")
                + "?CompanyInn=" + enc(session.companyInn()) + "&isDeserialized=true", body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new BusinessException("Cannot prepare Faktura content request");
        }
    }

    private Session session() {
        UUID companyId = CompanyContext.currentCompany();
        Company company = companies.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), companyId)
            .orElseThrow(() -> new BusinessException("Company not found"));
        String inn = digits(company.getTaxId());
        if (inn == null || inn.length() != 9) {
            throw new BusinessException("A nine-digit company INN is required for Faktura");
        }
        var endpoint = settings.endpoint(ExternalIntegrationSystem.FAKTURA_UZ);
        if (!endpoint.enabled() || endpoint.mockMode() || blank(endpoint.baseUrl())) {
            throw new BusinessException("Faktura live integration is disabled");
        }
        if (blank(endpoint.username()) || blank(endpoint.password()) || blank(endpoint.clientId()) || blank(endpoint.clientSecret())) {
            throw new BusinessException("Faktura credentials are incomplete");
        }
        requireSecureUrl(endpoint.baseUrl());
        requireSecureUrl(blank(endpoint.authPath()) ? AUTH_URL : api(endpoint, endpoint.authPath()));
        String fingerprint = Integer.toHexString(List.of(endpoint.username(), endpoint.password(), endpoint.clientId(),
            endpoint.clientSecret(), endpoint.baseUrl(), String.valueOf(endpoint.authPath())).hashCode());
        Token token = tokens.get(companyId);
        if (token == null || !fingerprint.equals(token.fingerprint()) || Instant.now().isAfter(token.expiresAt())) {
            token = authenticate(endpoint, fingerprint);
            tokens.put(companyId, token);
        }
        JsonNode account;
        try {
            account = authorizedRaw("GET", api(endpoint, "/Api/account/getuserdetails"), null, token.value());
        } catch (Unauthorized ex) {
            token = authenticate(endpoint, fingerprint);
            tokens.put(companyId, token);
            try {
                account = authorizedRaw("GET", api(endpoint, "/Api/account/getuserdetails"), null, token.value());
            } catch (Unauthorized secondFailure) {
                throw new BusinessException("Faktura rejected the account token");
            }
        }
        JsonNode accounts = field(account, "companies", "Companies");
        boolean found = accounts.isArray();
        if (found) {
            found = false;
            for (JsonNode item : accounts) {
                if (inn.equals(digits(text(item, "inn", "Inn")))) {
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            throw new BusinessException("The Faktura account does not have access to this company INN");
        }
        return new Session(endpoint, inn, token);
    }

    private Token authenticate(IntegrationSettingService.RuntimeEndpoint endpoint, String fingerprint) {
        String body = "grant_type=password&username=" + enc(endpoint.username()) + "&password=" + enc(endpoint.password())
            + "&client_id=" + enc(endpoint.clientId()) + "&client_secret=" + enc(endpoint.clientSecret());
        String url = blank(endpoint.authPath()) ? AUTH_URL : api(endpoint, endpoint.authPath());
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new BusinessException("Faktura authentication failed (HTTP " + response.statusCode() + ")");
            JsonNode value = json.readTree(response.body());
            String token = text(value, "access_token", "accessToken");
            if (blank(token)) throw new BusinessException("Faktura did not return an access token");
            long seconds = Math.max(30, Math.min(86400, value.path("expires_in").asLong(3600)) - 60);
            return new Token(token, Instant.now().plusSeconds(seconds), fingerprint);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("Faktura authentication connection failed");
        }
    }

    private JsonNode authorized(Session session, String method, String url, String body) {
        try {
            return authorizedRaw(method, url, body, session.token().value());
        } catch (Unauthorized ex) {
            tokens.remove(CompanyContext.currentCompany());
            Session renewed = session();
            try {
                return authorizedRaw(method, url, body, renewed.token().value());
            } catch (Unauthorized secondFailure) {
                throw new BusinessException("Faktura rejected the refreshed access token");
            }
        }
    }

    private JsonNode authorizedRaw(String method, String url, String body, String token) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(25))
                .header("Accept", "application/json").header("Authorization", "Bearer " + token);
            if ("POST".equals(method)) request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
            else request.GET();
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                Thread.sleep(1000);
                response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            }
            if (response.statusCode() == 401) throw new Unauthorized();
            if (response.statusCode() / 100 != 2) throw new BusinessException("Faktura returned HTTP " + response.statusCode());
            return json.readTree(response.body());
        } catch (BusinessException | Unauthorized ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("Faktura connection or response failed");
        }
    }

    private String api(IntegrationSettingService.RuntimeEndpoint endpoint, String path) {
        // Older saved settings acquired a leading slash when an absolute auth URL was entered.
        String normalized = path.startsWith("/https://") || path.startsWith("/http://") ? path.substring(1) : path;
        if (normalized.startsWith("https://") || normalized.startsWith("http://")) {
            requireSecureUrl(normalized);
            return normalized;
        }
        String base = endpoint.baseUrl().replaceAll("/+$", "");
        return base + (normalized.startsWith("/") ? normalized : "/" + normalized);
    }

    private static void requireSecureUrl(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if ("https".equalsIgnoreCase(uri.getScheme()) && host != null) return;
            if ("http".equalsIgnoreCase(uri.getScheme())
                && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host))) return;
        } catch (IllegalArgumentException ignored) { }
        throw new BusinessException("Faktura endpoint must use HTTPS");
    }

    static JsonNode field(JsonNode node, String first, String second) {
        JsonNode value = node.path(first);
        return value.isMissingNode() ? node.path(second) : value;
    }

    static String text(JsonNode node, String first, String second) {
        JsonNode value = field(node, first, second);
        return value.isMissingNode() || value.isNull() ? null : value.asText(null);
    }

    private static String digits(String value) { return value == null ? null : value.replaceAll("\\D", ""); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private record Token(String value, Instant expiresAt, String fingerprint) {}
    private record Session(IntegrationSettingService.RuntimeEndpoint endpoint, String companyInn, Token token) {}
    private static class Unauthorized extends RuntimeException {}
}
