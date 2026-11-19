package com.enterprise.mm.service;

import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.SmsGatewaySetting;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.config.SecurityIdentityProperties;
import com.enterprise.mm.repository.SmsGatewaySettingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SmsGatewayClient {
    private static final String DEFAULT_TEMPLATE = "ERP MM verification code: {code}. Valid for {ttlSeconds} seconds.";
    private static final String TWILIO_URL = "https://api.twilio.com/2010-04-01/Accounts/{accountSid}/Messages.json";
    private static final String ESKIZ_AUTH_URL = "https://notify.eskiz.uz/api/auth/login";
    private static final String ESKIZ_SEND_URL = "https://notify.eskiz.uz/api/message/sms/send";
    private final SecurityIdentityProperties properties;
    private final SmsGatewaySettingRepository repository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public boolean mfaEnabled() {
        return effectiveConfig().mfaEnabled();
    }
    public int ttlSeconds() {
        return effectiveConfig().ttlSeconds();
    }
    public boolean enabled() {
        SmsGatewayConfig config = effectiveConfig();
        return config.enabled() && !blank(resolveSendUrl(config));
    }
    public void send(User user, String code, String purpose) {
        SmsGatewayConfig config = effectiveConfig();
        if (!config.enabled()) {
            return;
        }
        String normalizedPurpose = blank(purpose) ? "MFA" : purpose;
        String text = template(config)
            .replace("{code}", code)
            .replace("{ttlSeconds}", Integer.toString(config.ttlSeconds()))
            .replace("{purpose}", normalizedPurpose);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenantId", TenantContext.currentTenant());
        metadata.put("username", user.getUsername());
        metadata.put("purpose", purpose);
        metadata.put("ttlSeconds", config.ttlSeconds());
        dispatch(config, user.getMfaPhoneNumber(), text, metadata);
    }
    public SmsGatewayResult sendTest(SmsGatewaySetting row, String phoneNumber) {
        SmsGatewayConfig config = row == null ? effectiveConfig() : fromRow(row);
        if (!config.enabled()) {
            throw new BusinessException("SMS-шлюз выключен");
        }
        String phone = blank(phoneNumber) ? "+998000000000" : phoneNumber;
        String text = template(config)
            .replace("{code}", "123456")
            .replace("{ttlSeconds}", Integer.toString(config.ttlSeconds()))
            .replace("{purpose}", "TEST");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenantId", TenantContext.currentTenant());
        metadata.put("purpose", "TEST");
        metadata.put("ttlSeconds", config.ttlSeconds());
        return dispatch(config, phone, text, metadata);
    }
    private SmsGatewayResult dispatch(SmsGatewayConfig config, String phoneNumber, String message, Map<String, Object> metadata) {
        if (blank(phoneNumber)) {
            throw new BusinessException("Phone number is required for SMS delivery");
        }
        String provider = normalizeProvider(config.providerCode());
        return switch (provider) {
            case "PLAYMOBILE" -> sendPlaymobile(config, phoneNumber, message);
            case "ESKIZ" -> sendEskiz(config, phoneNumber, message);
            case "TWILIO" -> sendTwilio(config, phoneNumber, message);
            default -> sendCustom(config, phoneNumber, message, metadata);
        };
    }
    private SmsGatewayResult sendCustom(SmsGatewayConfig config, String phoneNumber, String message, Map<String, Object> metadata) {
        String url = require(resolveSendUrl(config), "SMS gateway URL is required");
        Map<String, Object> payload = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        payload.put("phoneNumber", phoneNumber);
        payload.put("sender", config.sender());
        payload.put("message", message);
        HttpRequest.Builder builder = baseRequest(url, config).header("Content-Type", "application/json");
        if (!blank(config.authToken())) {
            builder.header(blank(config.authHeader()) ? "Authorization" : config.authHeader(), config.authToken());
        }
        return sendRequest(buildMethodRequest(builder, config.httpMethod(), json(payload)), "CUSTOM");
    }
    private SmsGatewayResult sendPlaymobile(SmsGatewayConfig config, String phoneNumber, String message) {
        String url = require(resolveSendUrl(config), "Playmobile send URL is required");
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("text", message);
        Map<String, Object> sms = new LinkedHashMap<>();
        sms.put("originator", firstNonBlank(config.sender(), config.fromPhone(), "ERP-MM"));
        sms.put("content", content);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("recipient", digits(phoneNumber));
        item.put("message-id", UUID.randomUUID().toString());
        item.put("sms", sms);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messages", List.of(item));
        HttpRequest.Builder builder = baseRequest(url, config).header("Content-Type", "application/json");
        applyBasicOrToken(builder, config);
        return sendRequest(builder.POST(HttpRequest.BodyPublishers.ofString(json(payload))).build(), "PLAYMOBILE");
    }
    private SmsGatewayResult sendEskiz(SmsGatewayConfig config, String phoneNumber, String message) {
        String url = firstNonBlank(resolveSendUrl(config), ESKIZ_SEND_URL);
        String token = firstNonBlank(config.authToken(), authenticateEskiz(config));
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("mobile_phone", digits(phoneNumber));
        fields.put("message", message);
        fields.put("from", firstNonBlank(config.sender(), "4546"));
        HttpRequest.Builder builder = baseRequest(url, config)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Authorization", token.startsWith("Bearer ") ? token : "Bearer " + token);
        return sendRequest(builder.POST(HttpRequest.BodyPublishers.ofString(form(fields))).build(), "ESKIZ");
    }
    private String authenticateEskiz(SmsGatewayConfig config) {
        String authUrl = firstNonBlank(config.authUrl(), ESKIZ_AUTH_URL);
        String login = require(config.login(), "Eskiz login/email is required");
        String password = require(config.password(), "Eskiz password is required");
        Map<String, String> credentials = new LinkedHashMap<>();
        credentials.put("email", login);
        credentials.put("password", password);
        HttpRequest request = HttpRequest.newBuilder(URI.create(authUrl))
            .timeout(Duration.ofSeconds(Math.max(1, config.timeoutSeconds())))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form(credentials)))
            .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException("Eskiz auth returned HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            String token = firstNonBlank(
                root.path("data").path("token").asText(null),
                root.path("token").asText(null),
                root.path("access_token").asText(null)
            );
            if (blank(token)) {
                throw new BusinessException("Eskiz auth response does not contain token");
            }
            return token;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("Eskiz auth request failed: " + ex.getMessage());
        }
    }
    private SmsGatewayResult sendTwilio(SmsGatewayConfig config, String phoneNumber, String message) {
        String accountSid = require(config.accountSid(), "Twilio Account SID is required");
        String authToken = require(config.authToken(), "Twilio Auth Token is required");
        String url = firstNonBlank(config.url(), TWILIO_URL).replace("{accountSid}", encodePath(accountSid));
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("To", phoneNumber);
        fields.put("Body", message);
        if (!blank(config.serviceSid())) {
            fields.put("MessagingServiceSid", config.serviceSid());
        } else {
            fields.put("From", require(firstNonBlank(config.fromPhone(), config.sender()), "Twilio From phone or Messaging Service SID is required"));
        }
        HttpRequest.Builder builder = baseRequest(url, config)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Authorization", "Basic " + basic(accountSid, authToken));
        return sendRequest(builder.POST(HttpRequest.BodyPublishers.ofString(form(fields))).build(), "TWILIO");
    }
    private SmsGatewayResult sendRequest(HttpRequest request, String provider) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(provider + " SMS gateway returned HTTP " + response.statusCode());
            }
            return new SmsGatewayResult(provider, response.statusCode());
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(provider + " SMS gateway request failed: " + ex.getMessage());
        }
    }
    private HttpRequest.Builder baseRequest(String url, SmsGatewayConfig config) {
        return HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(Math.max(1, config.timeoutSeconds())));
    }
    private HttpRequest buildMethodRequest(HttpRequest.Builder builder, String method, String body) {
        return "GET".equalsIgnoreCase(method)
            ? builder.GET().build()
            : builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
    private void applyBasicOrToken(HttpRequest.Builder builder, SmsGatewayConfig config) {
        if (!blank(config.authToken())) {
            builder.header(blank(config.authHeader()) ? "Authorization" : config.authHeader(), config.authToken());
            return;
        }
        if (!blank(config.login()) && !blank(config.password())) {
            builder.header("Authorization", "Basic " + basic(config.login(), config.password()));
        }
    }
    private SmsGatewayConfig effectiveConfig() {
        SmsGatewaySetting row = currentRow();
        return isDatabaseConfigured(row) ? fromRow(row) : fromProperties();
    }
    private SmsGatewaySetting currentRow() {
        return repository.findFirstByTenantIdAndIsDeletedFalseOrderByCreatedAtAsc(TenantContext.currentTenant()).orElse(null);
    }
    private boolean isDatabaseConfigured(SmsGatewaySetting row) {
        return row != null && (
            row.isEnabled()
                || row.isMfaEnabled()
                || !blank(row.getUrl())
                || !blank(row.getAuthUrl())
                || !blank(row.getAuthToken())
                || !blank(row.getLogin())
                || !blank(row.getPassword())
                || !blank(row.getAccountSid())
                || !blank(row.getServiceSid())
                || !"CUSTOM".equals(normalizeProvider(row.getProviderCode()))
        );
    }
    private SmsGatewayConfig fromRow(SmsGatewaySetting row) {
        return new SmsGatewayConfig(
            normalizeProvider(row.getProviderCode()),
            row.isEnabled(),
            row.isMfaEnabled(),
            row.getUrl(),
            row.getAuthUrl(),
            row.getHttpMethod(),
            row.getAuthHeader(),
            row.getAuthToken(),
            row.getLogin(),
            row.getPassword(),
            row.getAccountSid(),
            row.getServiceSid(),
            row.getFromPhone(),
            row.getSender(),
            row.getTemplate(),
            row.getTimeoutSeconds(),
            row.getTtlSeconds()
        );
    }
    private SmsGatewayConfig fromProperties() {
        var sms = properties.getSmsMfa();
        var gateway = sms.getGateway();
        return new SmsGatewayConfig(
            normalizeProvider(gateway.getProviderCode()),
            gateway.isEnabled(),
            sms.isEnabled(),
            gateway.getUrl(),
            gateway.getAuthUrl(),
            gateway.getMethod(),
            gateway.getAuthHeader(),
            gateway.getAuthToken(),
            gateway.getLogin(),
            gateway.getPassword(),
            gateway.getAccountSid(),
            gateway.getServiceSid(),
            gateway.getFromPhone(),
            gateway.getSender(),
            gateway.getTemplate(),
            gateway.getTimeoutSeconds(),
            sms.getTtlSeconds()
        );
    }
    private String resolveSendUrl(SmsGatewayConfig config) {
        String provider = normalizeProvider(config.providerCode());
        if ("TWILIO".equals(provider)) {
            return firstNonBlank(config.url(), TWILIO_URL);
        }
        if ("ESKIZ".equals(provider)) {
            return firstNonBlank(config.url(), ESKIZ_SEND_URL);
        }
        return config.url();
    }
    private String template(SmsGatewayConfig config) {
        return firstNonBlank(config.template(), DEFAULT_TEMPLATE);
    }
    private String normalizeProvider(String value) {
        if (blank(value)) {
            return "CUSTOM";
        }
        String normalized = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        return "TWILLO".equals(normalized) ? "TWILIO" : normalized;
    }
    private String json(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new BusinessException("Cannot serialize SMS payload");
        }
    }
    private String form(Map<String, String> fields) {
        StringBuilder builder = new StringBuilder();
        fields.forEach((key, value) -> {
            if (blank(key) || blank(value)) {
                return;
            }
            if (!builder.isEmpty()) {
                builder.append('&');
            }
            builder.append(URLEncoder.encode(key, StandardCharsets.UTF_8));
            builder.append('=');
            builder.append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return builder.toString();
    }
    private String basic(String username, String password) {
        return Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
    private String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
    private String digits(String value) {
        return value == null ? null : value.replaceAll("\\D", "");
    }
    private String require(String value, String message) {
        if (blank(value)) {
            throw new BusinessException(message);
        }
        return value;
    }
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!blank(value)) {
                return value.trim();
            }
        }
        return null;
    }
    public record SmsGatewayResult(String provider, int statusCode) {
    }
    private record SmsGatewayConfig(
        String providerCode,
        boolean enabled,
        boolean mfaEnabled,
        String url,
        String authUrl,
        String httpMethod,
        String authHeader,
        String authToken,
        String login,
        String password,
        String accountSid,
        String serviceSid,
        String fromPhone,
        String sender,
        String template,
        int timeoutSeconds,
        int ttlSeconds
    ) {
    }
}
