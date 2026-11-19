package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.enums.IntegrationEventStatus;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.config.IntegrationProperties;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IntegrationDispatchService {
    private final com.enterprise.mm.security.pilot.PilotCapabilityPolicy pilotCapabilityPolicy;
    public static final int MAX_AUTOMATIC_RETRIES = 5;
    private final IntegrationEventRepository eventRepository;
    private final AuditService auditService;
    private final IntegrationProperties properties;
    private final IntegrationSettingService settingService;
    private final IntegrationMessageQueuePublisher queuePublisher;
    private final ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private com.enterprise.mm.erp.OneCTransport oneCTransport;
    @org.springframework.beans.factory.annotation.Autowired
    private com.enterprise.mm.treasury.TreasuryNativeAccess treasuryNativeAccess;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void scheduledDispatch() {
        dispatchDue();
    }
    @Transactional
    public int dispatchDue() {
        int count = 0;
        LocalDateTime now = LocalDateTime.now();
        for (IntegrationEvent event : eventRepository.findDue(List.of(IntegrationEventStatus.NEW.name(), IntegrationEventStatus.RETRY.name()), now)) {
            if (!pilotCapabilityPolicy.permitsIntegration(event)) continue;
            if (oneCTransport!=null&&oneCTransport.pullEnabled()&&externalSystem(event).orElse(null)==ExternalIntegrationSystem.ONE_C) continue;
            if (treasuryNativeAccess!=null&&treasuryNativeAccess.nativeEnabled()&&treasuryEvent(event)) continue;
            String previousTenant = TenantContext.currentTenant();
            UUID previousCompany = CompanyContext.currentCompany();
            TenantContext.setTenant(event.getTenantId());
            CompanyContext.setCompany(event.getCompanyId());
            try {
                dispatch(event.getId());
                count++;
            } finally {
                TenantContext.setTenant(previousTenant);
                CompanyContext.setCompany(previousCompany);
            }
        }
        return count;
    }
    @Transactional
    public IntegrationEvent dispatch(UUID id) {
        IntegrationEvent event = eventRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), id)
            .orElseThrow(() -> new BusinessException("Integration event not found"));
        if (!com.enterprise.mm.security.AccessScope.canAccessCompany(event.getCompanyId())) {
            throw new BusinessException("Integration event not found");
        }
        pilotCapabilityPolicy.requireIntegration(event);
        if(treasuryNativeAccess!=null&&treasuryNativeAccess.nativeEnabled()&&treasuryEvent(event))
            throw new BusinessException("Treasury uses the native-initiated durable queue; outbound HTTP/Rabbit dispatch is disabled");
        if (IntegrationEventStatus.PUBLISHED.name().equals(event.getStatus())||IntegrationEventStatus.DELIVERED.name().equals(event.getStatus())) {
            return event;
        }
        if (IntegrationEventStatus.CANCELLED.name().equals(event.getStatus())
            || IntegrationEventStatus.DEAD.name().equals(event.getStatus())) {
            throw new BusinessException("Integration event requires an explicit operator decision before dispatch");
        }
        if(oneCTransport!=null&&oneCTransport.pullEnabled()&&externalSystem(event).orElse(null)==ExternalIntegrationSystem.ONE_C)
            throw new BusinessException("1C uses the native-initiated durable exchange queue");
        event.setLastAttemptAt(LocalDateTime.now());
        try {
            Optional<ExternalIntegrationSystem> system = externalSystem(event);
            if (system.isPresent() && shouldUseSystemEndpoint(settingService.endpoint(system.get()))) {
                IntegrationSettingService.RuntimeEndpoint endpoint = settingService.endpoint(system.get());
                String payload = toEndpointPayload(event);
                post(endpoint.uri(targetPath(event, endpoint)), endpoint, event, payload);
                event.setLastError("Delivered to " + system.get().name() + " endpoint");
            } else if (properties.getWebhookUrl() != null && !properties.getWebhookUrl().isBlank()) {
                String payload = toPayload(event);
                post(properties.getWebhookUrl(), null, event, payload);
                event.setLastError("Delivered to integration webhook");
            } else if (queuePublisher.enabled()) {
                String payload = toPayload(event);
                queuePublisher.publish(event, payload);
                event.setLastError("Published to RabbitMQ exchange " + properties.getMessageQueue().getExchange());
            } else {
                throw new BusinessException("No integration transport is configured");
            }
            event.setStatus(IntegrationEventStatus.PUBLISHED.name());
            event.setNextRetryAt(null);
            IntegrationEvent saved = eventRepository.save(event);
            auditService.log("IntegrationEvent", saved.getId(), "DISPATCH", saved.getEventType(), null, null);
            return saved;
        } catch (Exception ex) {
            int retryCount = event.getRetryCount() == null ? 1 : event.getRetryCount() + 1;
            event.setRetryCount(retryCount);
            if (retryCount >= MAX_AUTOMATIC_RETRIES) {
                event.setStatus(IntegrationEventStatus.DEAD.name());
                event.setNextRetryAt(null);
            } else {
                event.setStatus(IntegrationEventStatus.RETRY.name());
                event.setNextRetryAt(LocalDateTime.now().plusMinutes(Math.min(60, retryCount * 5L)));
            }
            event.setLastError(ex.getMessage());
            IntegrationEvent saved = eventRepository.save(event);
            auditService.log("IntegrationEvent", saved.getId(), "DISPATCH_FAILED", ex.getMessage(), null, null);
            return saved;
        }
    }
    @Transactional(readOnly = true)
    public List<IntegrationEvent> pending() {
        return eventRepository.findByTenantIdAndStatusInAndIsDeletedFalse(TenantContext.currentTenant(), List.of(IntegrationEventStatus.NEW.name(), IntegrationEventStatus.RETRY.name()))
            .stream()
            .filter(event -> com.enterprise.mm.security.AccessScope.canAccessCompany(event.getCompanyId()))
            .toList();
    }
    private Optional<ExternalIntegrationSystem> externalSystem(IntegrationEvent event) {
        if (event.getAggregateType() == null || event.getAggregateType().isBlank()) {
            return Optional.empty();
        }
        for (ExternalIntegrationSystem system : ExternalIntegrationSystem.values()) {
            if (system.name().equals(event.getAggregateType())) {
                return Optional.of(system);
            }
        }
        return Optional.empty();
    }
    private boolean treasuryEvent(IntegrationEvent event) {
        return ExternalIntegrationSystem.TREASURY.name().equalsIgnoreCase(event.getAggregateType())
            ||event.getEventType()!=null&&event.getEventType().toUpperCase(java.util.Locale.ROOT).startsWith("TREASURY.");
    }
    private boolean shouldUseSystemEndpoint(IntegrationSettingService.RuntimeEndpoint endpoint) {
        return endpoint.enabled()
            && !endpoint.mockMode()
            && endpoint.baseUrl() != null
            && !endpoint.baseUrl().isBlank();
    }
    private void post(String uri, IntegrationSettingService.RuntimeEndpoint endpoint, IntegrationEvent event, String payload) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uri))
            .timeout(Duration.ofSeconds(10))
            .header("Idempotency-Key", event.getIdempotencyKey() == null ? event.getId().toString() : event.getIdempotencyKey())
            .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (endpoint == null) {
            builder.header("Content-Type", "application/json");
        } else {
            headers(endpoint, event, payload).forEach(builder::header);
        }
        HttpRequest request = builder.build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BusinessException("Integration endpoint returned HTTP " + response.statusCode() + responseBodySuffix(response.body()));
        }
    }
    private String responseBodySuffix(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String compact = body.replaceAll("\\s+", " ").trim();
        int maxLength = 500;
        return ": " + (compact.length() > maxLength ? compact.substring(0, maxLength) : compact);
    }
    private Map<String, String> headers(IntegrationSettingService.RuntimeEndpoint endpoint, IntegrationEvent event, String payload) {
        Map<String, String> headers = new LinkedHashMap<>(endpoint.headers());
        if ("OAUTH2".equals(endpoint.authType())) {
            headers.put("Authorization", "Bearer " + oauthToken(endpoint));
        }
        headers.put("X-MM-Integration-System", endpoint.systemCode());
        headers.put("X-MM-Event-Id", event.getId().toString());
        headers.put("X-MM-Tenant-Id", event.getTenantId());
        headers.put("X-MM-Company-Id", event.getCompanyId().toString());
        if (requiresMesRoleHeader(endpoint.systemCode())) {
            headers.put("X-Role", "INTEGRATION_OPERATOR");
            headers.put("X-Idempotency-Key", event.getIdempotencyKey() == null ? event.getId().toString() : event.getIdempotencyKey());
        }
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        headers.put("X-MM-Timestamp", timestamp);
        if (endpoint.clientSecret() != null && !endpoint.clientSecret().isBlank()) {
            headers.put("X-MM-Signature", hmac(endpoint.clientSecret(), signingEventId(event, payload) + "|" + timestamp + "|" + payload));
        }
        return headers;
    }

    /** Raw Integration Core envelopes own their event ID; legacy payloads use the local outbox ID. */
    private String signingEventId(IntegrationEvent event, String payload) {
        try {
            JsonNode body = objectMapper.readTree(payload);
            if (body.hasNonNull("id") && !body.get("id").asText().isBlank()) {
                return body.get("id").asText();
            }
        } catch (JsonProcessingException ignored) {
            // The payload is still delivered and the local event ID remains a safe fallback.
        }
        return event.getId().toString();
    }
    private boolean requiresMesRoleHeader(String systemCode) {
        return ExternalIntegrationSystem.PRODUCTION.name().equals(systemCode)
            || ExternalIntegrationSystem.WMS.name().equals(systemCode)
            || ExternalIntegrationSystem.SCADA.name().equals(systemCode);
    }
    private String oauthToken(IntegrationSettingService.RuntimeEndpoint endpoint) {
        if (endpoint.clientId() == null || endpoint.clientId().isBlank() || endpoint.clientSecret() == null || endpoint.clientSecret().isBlank()) {
            throw new BusinessException("OAuth2 client credentials are not configured for " + endpoint.systemCode());
        }
        String authPath = endpoint.authPath() == null || endpoint.authPath().isBlank() ? "/oauth/token" : endpoint.authPath();
        String form = "grant_type=client_credentials&client_id=" + url(endpoint.clientId());
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint.uri(authPath)))
            .timeout(Duration.ofSeconds(12))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((endpoint.clientId() + ":" + endpoint.clientSecret()).getBytes(StandardCharsets.UTF_8)))
            .POST(HttpRequest.BodyPublishers.ofString(form));
        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException("OAuth2 token endpoint returned HTTP " + response.statusCode());
            }
            JsonNode token = objectMapper.readTree(response.body());
            if (!token.hasNonNull("access_token")) {
                throw new BusinessException("OAuth2 token response does not contain access_token");
            }
            return token.get("access_token").asText();
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("OAuth2 token request failed: " + ex.getMessage());
        }
    }
    private String hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BusinessException("Cannot sign integration payload");
        }
    }
    private String url(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
    private String targetPath(IntegrationEvent event, IntegrationSettingService.RuntimeEndpoint endpoint) {
        JsonNode node = payloadNode(event);
        if (node.hasNonNull("targetPath")) {
            return node.get("targetPath").asText();
        }
        JsonNode payload = node.path("payload");
        if (payload.hasNonNull("targetPath")) {
            return payload.get("targetPath").asText();
        }
        return endpoint.eventsPath();
    }
    private String toPayload(IntegrationEvent event) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", event.getId());
        payload.put("eventType", event.getEventType());
        payload.put("aggregateType", event.getAggregateType());
        payload.put("aggregateId", event.getAggregateId());
        payload.put("payload", payloadNode(event));
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("Cannot serialize integration event");
        }
    }
    private String toEndpointPayload(IntegrationEvent event) {
        JsonNode node = payloadNode(event);
        JsonNode raw = node.path("raw");
        if (!hasMeaningfulRaw(raw)) {
            raw = node.path("payload").path("raw");
        }
        JsonNode body = hasMeaningfulRaw(raw) ? raw : node;
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("Cannot serialize integration endpoint payload");
        }
    }
    private boolean hasMeaningfulRaw(JsonNode raw) {
        return raw != null
            && !raw.isMissingNode()
            && !raw.isNull()
            && !(raw.isObject() && raw.isEmpty())
            && !(raw.isArray() && raw.isEmpty())
            && !(raw.isTextual() && raw.asText().isBlank());
    }
    private JsonNode payloadNode(IntegrationEvent event) {
        if (event.getPayload() == null || event.getPayload().isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(event.getPayload());
        } catch (JsonProcessingException ex) {
            return objectMapper.createObjectNode().put("rawPayload", event.getPayload());
        }
    }
}
