package com.enterprise.mm.service;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationHealthDto;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationSendRequest;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationSyncTestRequest;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationSystemDto;
import com.enterprise.mm.payload.IntegrationEventCreateRequest;
import com.enterprise.mm.payload.IntegrationEventResponse;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.IntegrationEventMapper;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.config.IntegrationProperties;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ExternalIntegrationService {
    private final IntegrationProperties properties;
    private final IntegrationEventRepository eventRepository;
    private final IntegrationEventService eventService;
    private final IntegrationDispatchService dispatchService;
    private final IntegrationEventMapper eventMapper;
    private final IntegrationSettingService settingService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Transactional(readOnly = true)
    public List<IntegrationSystemDto> listSystems() {
        return listSystems(null, List.of(), null, null);
    }

    @Transactional(readOnly = true)
    public List<IntegrationSystemDto> listSystems(String search, List<String> status, LocalDate dateFrom, LocalDate dateTo) {
        return Arrays.stream(ExternalIntegrationSystem.values())
            .map(this::toSystemDto)
            .filter(item -> matchesSearch(item, search))
            .filter(item -> matchesStatus(item, status))
            .filter(item -> matchesDate(item, dateFrom, dateTo))
            .toList();
    }
    @Transactional(readOnly = true)
    public IntegrationHealthDto health(ExternalIntegrationSystem system) {
        return settingService.health(system);
    }
    @Transactional
    public IntegrationEventResponse enqueue(ExternalIntegrationSystem system, IntegrationSendRequest request, String headerIdempotencyKey) {
        String eventType = normalizeEventType(system, request.eventType());
        String payload = envelope(system, eventType, request.aggregateId(), request.payload());
        IntegrationEventCreateRequest dto = new IntegrationEventCreateRequest();
        dto.setCode(eventType + "-" + UUID.randomUUID().toString().substring(0, 8));
        dto.setName(system.displayName() + " " + eventType);
        dto.setEventType(eventType);
        dto.setAggregateType(system.name());
        dto.setAggregateId(request.aggregateId());
        dto.setPayload(payload);
        String idempotencyKey = firstNonBlank(headerIdempotencyKey, request.idempotencyKey());
        return eventService.createIdempotent(dto, idempotencyKey);
    }
    @Transactional
    public IntegrationEventResponse syncTest(ExternalIntegrationSystem system, IntegrationSyncTestRequest request, String headerIdempotencyKey) {
        String scenario = request.scenario() == null || request.scenario().isBlank() ? "CONNECTIVITY_TEST" : request.scenario();
        IntegrationSendRequest sendRequest = new IntegrationSendRequest(
            scenario,
            null,
            request.payload() == null ? Map.of("test", true) : request.payload(),
            request.idempotencyKey()
        );
        IntegrationEventResponse queued = enqueue(system, sendRequest, headerIdempotencyKey);
        IntegrationEvent dispatched = dispatchService.dispatch(queued.getId());
        return eventMapper.toResponse(dispatched);
    }
    private boolean matchesSearch(IntegrationSystemDto item, String search) {
        if (search == null || search.isBlank()) {
            return true;
        }
        String needle = search.trim().toLowerCase(java.util.Locale.ROOT);
        return contains(item.code(), needle)
            || contains(item.name(), needle)
            || contains(item.description(), needle)
            || contains(item.status(), needle)
            || contains(item.baseUrl(), needle)
            || contains(item.lastEventStatus(), needle)
            || item.capabilities().stream().anyMatch(value -> contains(value, needle));
    }

    private boolean matchesStatus(IntegrationSystemDto item, List<String> status) {
        if (status == null || status.isEmpty()) {
            return true;
        }
        return status.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.trim().toUpperCase(java.util.Locale.ROOT))
            .anyMatch(value -> value.equalsIgnoreCase(item.status()) || value.equalsIgnoreCase(item.lastEventStatus()));
    }

    private boolean matchesDate(IntegrationSystemDto item, LocalDate dateFrom, LocalDate dateTo) {
        if (dateFrom == null && dateTo == null) {
            return true;
        }
        if (item.lastEventAt() == null) {
            return false;
        }
        LocalDate eventDate = item.lastEventAt().toLocalDate();
        return (dateFrom == null || !eventDate.isBefore(dateFrom)) && (dateTo == null || !eventDate.isAfter(dateTo));
    }

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(needle);
    }

    private IntegrationSystemDto toSystemDto(ExternalIntegrationSystem system) {
        IntegrationSettingService.RuntimeEndpoint endpoint = settingService.endpoint(system);
        boolean configured = endpoint.configured();
        long pending = settingService.pendingEvents(system);
        IntegrationEvent last = settingService.lastEvent(system).orElse(null);
        return new IntegrationSystemDto(
            system.name(),
            system.displayName(),
            system.description(),
            endpoint.enabled(),
            configured,
            endpoint.mockMode(),
            status(endpoint, configured),
            configured ? endpoint.baseUrl() : "",
            system.capabilities(),
            pending,
            last == null ? null : last.getStatus(),
            last == null ? null : last.getCreatedAt()
        );
    }
    private boolean hasBaseUrl(IntegrationProperties.SystemEndpoint endpoint) {
        return endpoint.getBaseUrl() != null && !endpoint.getBaseUrl().isBlank();
    }
    private String status(IntegrationProperties.SystemEndpoint endpoint, boolean configured) {
        if (!endpoint.isEnabled()) {
            return "DISABLED";
        }
        if (endpoint.isMockMode()) {
            return "MOCK_CONFIGURED";
        }
        if (!configured) {
            return "MOCK";
        }
        return "LIVE_CONFIGURED";
    }
    private String status(IntegrationSettingService.RuntimeEndpoint endpoint, boolean configured) {
        if (!endpoint.enabled()) {
            return "DISABLED";
        }
        if (endpoint.mockMode()) {
            return "MOCK_CONFIGURED";
        }
        if (!configured) {
            return "MOCK";
        }
        return "LIVE_CONFIGURED";
    }
    private String normalizeEventType(ExternalIntegrationSystem system, String eventType) {
        if (eventType == null || eventType.isBlank()) {
            throw new BusinessException("eventType is required");
        }
        String normalized = eventType.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        return normalized.startsWith(system.name() + ".") ? normalized : system.name() + "." + normalized;
    }
    private String envelope(ExternalIntegrationSystem system, String eventType, UUID aggregateId, Object rawPayload) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("source", "MM");
        envelope.put("targetSystem", system.name());
        envelope.put("eventType", eventType);
        if (aggregateId != null) {
            envelope.put("aggregateId", aggregateId.toString());
        }
        envelope.put("createdAt", LocalDateTime.now().toString());
        envelope.set("payload", payloadNode(rawPayload));
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("Cannot serialize integration payload");
        }
    }
    private JsonNode payloadNode(Object rawPayload) {
        if (rawPayload == null) {
            return objectMapper.createObjectNode();
        }
        if (rawPayload instanceof JsonNode jsonNode) {
            return jsonNode;
        }
        if (rawPayload instanceof String payload) {
            if (payload.isBlank()) {
                return objectMapper.createObjectNode();
            }
            try {
                return objectMapper.readTree(payload);
            } catch (JsonProcessingException ex) {
                ObjectNode wrapper = objectMapper.createObjectNode();
                wrapper.put("rawPayload", payload);
                return wrapper;
            }
        }
        return objectMapper.valueToTree(rawPayload);
    }
    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }
    private String endpointUri(String baseUrl, String path) {
        String cleanBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String cleanPath = path == null || path.isBlank() ? "/health" : path;
        if (!cleanPath.startsWith("/")) {
            cleanPath = "/" + cleanPath;
        }
        return cleanBase + cleanPath;
    }
}

