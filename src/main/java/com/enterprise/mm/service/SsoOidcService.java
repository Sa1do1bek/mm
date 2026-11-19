package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.AuthDtos.LoginResponse;
import com.enterprise.mm.payload.SecurityIdentityDtos.SsoLoginUrlResponse;
import com.enterprise.mm.payload.SecurityIdentityDtos.SsoOidcCallbackRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.service.SsoSettingsService.RuntimeSsoSettings;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SsoOidcService {
    private final SsoSettingsService settingsService;
    private final SsoAssertionService ssoAssertionService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public SsoLoginUrlResponse loginUrl(String tenantId, UUID companyId) {
        String normalizedTenant = TenantContext.normalize(tenantId);
        UUID normalizedCompany = companyId == null ? CompanyContext.DEFAULT_COMPANY_ID : companyId;
        RuntimeSsoSettings sso = settingsService.runtime(normalizedTenant, normalizedCompany);
        requireEnabled(sso);
        if (!"OIDC".equalsIgnoreCase(sso.providerType())) {
            throw new BusinessException("SSO provider type " + sso.providerType() + " uses assertion gateway, not OIDC code flow");
        }
        String authorizationUrl = firstNonBlank(sso.authorizationUrl(), sso.loginUrl());
        if (!hasText(authorizationUrl) || !hasText(sso.clientId()) || !hasText(sso.redirectUri())) {
            throw new BusinessException("OIDC authorization URL, client ID and redirect URI must be configured");
        }
        long issuedAt = Instant.now().getEpochSecond();
        long expiresAt = issuedAt + Math.max(60, sso.timestampSkewSeconds());
        String state = state(normalizedTenant, normalizedCompany, issuedAt, sso);
        String url = authorizationUrl
            + separator(authorizationUrl)
            + "response_type=code"
            + "&client_id=" + enc(sso.clientId())
            + "&redirect_uri=" + enc(sso.redirectUri())
            + "&scope=" + enc(String.join(" ", sso.scopes() == null || sso.scopes().isEmpty() ? List.of("openid", "profile", "email") : sso.scopes()))
            + "&state=" + enc(state);
        return new SsoLoginUrlResponse(url, state, expiresAt);
    }
    @Transactional
    public LoginResponse callback(SsoOidcCallbackRequest request) {
        if (request == null || !hasText(request.code()) || !hasText(request.state())) {
            throw new BusinessException("OIDC code and state are required");
        }
        ValidatedState state = validateState(request.state());
        JsonNode token = exchangeCode(request.code(), state.sso());
        JsonNode claims = claims(token, state.sso());
        String username = firstNonBlank(text(claims, "preferred_username"), text(claims, "email"), text(claims, "sub"));
        String fullName = firstNonBlank(text(claims, "name"), username);
        String email = text(claims, "email");
        List<String> roles = roles(claims);
        UUID companyId = UUID.fromString(state.payload().path("companyId").asText(CompanyContext.DEFAULT_COMPANY_ID.toString()));
        return ssoAssertionService.authenticateClaims(
            state.payload().path("tenantId").asText(TenantContext.DEFAULT_TENANT),
            companyId,
            username,
            email,
            fullName,
            roles,
            "OIDC"
        );
    }
    private JsonNode exchangeCode(String code, RuntimeSsoSettings sso) {
        if (!hasText(sso.tokenUrl())) {
            throw new BusinessException("OIDC token URL is not configured");
        }
        String form = "grant_type=authorization_code"
            + "&code=" + enc(code)
            + "&redirect_uri=" + enc(sso.redirectUri())
            + "&client_id=" + enc(sso.clientId());
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(sso.tokenUrl()))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form + clientSecretFormPart(sso)));
        if ("BASIC".equalsIgnoreCase(sso.clientAuthMethod()) && hasText(sso.clientSecret())) {
            String basic = Base64.getEncoder().encodeToString((sso.clientId() + ":" + sso.clientSecret()).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + basic);
        }
        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException("OIDC token endpoint returned HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("OIDC token exchange failed: " + ex.getMessage());
        }
    }
    private JsonNode claims(JsonNode token, RuntimeSsoSettings sso) {
        String accessToken = text(token, "access_token");
        String idToken = text(token, "id_token");
        if (hasText(sso.userInfoUrl()) && hasText(accessToken)) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(sso.userInfoUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return objectMapper.readTree(response.body());
                }
                throw new BusinessException("OIDC userinfo endpoint returned HTTP " + response.statusCode());
            } catch (BusinessException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new BusinessException("OIDC userinfo failed: " + ex.getMessage());
            }
        }
        if (hasText(idToken)) {
            return decodeIdTokenClaims(idToken, sso);
        }
        throw new BusinessException("OIDC token response does not contain user claims");
    }
    private JsonNode decodeIdTokenClaims(String idToken, RuntimeSsoSettings sso) {
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length < 2) {
                throw new BusinessException("Invalid OIDC ID token");
            }
            JsonNode claims = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            long now = Instant.now().getEpochSecond();
            if (claims.has("exp") && claims.path("exp").asLong() < now) {
                throw new BusinessException("OIDC ID token is expired");
            }
            if (hasText(sso.clientId()) && claims.has("aud") && !audienceContains(claims.path("aud"), sso.clientId())) {
                throw new BusinessException("OIDC ID token audience mismatch");
            }
            return claims;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("Cannot read OIDC ID token claims");
        }
    }
    private boolean audienceContains(JsonNode aud, String clientId) {
        if (aud.isArray()) {
            for (JsonNode item : aud) {
                if (clientId.equals(item.asText())) {
                    return true;
                }
            }
            return false;
        }
        return clientId.equals(aud.asText());
    }
    private String state(String tenantId, UUID companyId, long issuedAt, RuntimeSsoSettings sso) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("tenantId", tenantId);
        node.put("companyId", companyId.toString());
        node.put("issuedAt", issuedAt);
        node.put("nonce", UUID.randomUUID().toString());
        try {
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(node));
            return payload + "." + sign(payload, sso);
        } catch (Exception ex) {
            throw new BusinessException("Cannot create OIDC state");
        }
    }
    private ValidatedState validateState(String state) {
        String[] parts = state.split("\\.");
        if (parts.length != 2) {
            throw new BusinessException("Invalid OIDC state");
        }
        try {
            JsonNode node = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
            String tenantId = node.path("tenantId").asText(TenantContext.DEFAULT_TENANT);
            UUID companyId = UUID.fromString(node.path("companyId").asText(CompanyContext.DEFAULT_COMPANY_ID.toString()));
            RuntimeSsoSettings sso = settingsService.runtime(tenantId, companyId);
            requireEnabled(sso);
            if (!MessageDigest.isEqual(sign(parts[0], sso).getBytes(StandardCharsets.UTF_8), parts[1].getBytes(StandardCharsets.UTF_8))) {
                throw new BusinessException("Invalid OIDC state");
            }
            long issuedAt = node.path("issuedAt").asLong(0);
            if (Instant.now().getEpochSecond() - issuedAt > Math.max(60, sso.timestampSkewSeconds())) {
                throw new BusinessException("OIDC state is expired");
            }
            return new ValidatedState(node, sso);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("Cannot parse OIDC state");
        }
    }
    private String sign(String payload, RuntimeSsoSettings sso) {
        String secret = firstNonBlank(sso.stateSecret(), sso.assertionSecret());
        if (!hasText(secret)) {
            throw new BusinessException("OIDC state secret is not configured");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BusinessException("Cannot sign OIDC state");
        }
    }
    private List<String> roles(JsonNode claims) {
        List<String> roles = new ArrayList<>();
        addTextArray(roles, claims.path("roles"));
        addTextArray(roles, claims.path("groups"));
        addTextArray(roles, claims.path("authorities"));
        addTextArray(roles, claims.path("realm_access").path("roles"));
        if (roles.isEmpty()) {
            roles.add("VIEWER");
        }
        return roles.stream().map(role -> role.toUpperCase(Locale.ROOT).replace("ROLE_", "")).distinct().toList();
    }
    private void addTextArray(List<String> target, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> target.add(item.asText()));
        } else if (node.isTextual()) {
            target.add(node.asText());
        }
    }
    private String clientSecretFormPart(RuntimeSsoSettings sso) {
        if ("POST".equalsIgnoreCase(sso.clientAuthMethod()) && hasText(sso.clientSecret())) {
            return "&client_secret=" + enc(sso.clientSecret());
        }
        return "";
    }
    private void requireEnabled(RuntimeSsoSettings sso) {
        if (!sso.enabled()) {
            throw new BusinessException("SSO is not enabled");
        }
    }
    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
    private String separator(String url) {
        return url.contains("?") ? "&" : "?";
    }
    private String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
    private record ValidatedState(JsonNode payload, RuntimeSsoSettings sso) {
    }
}

