package com.enterprise.mm.service;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.AttachmentDto;
import com.enterprise.mm.payload.ExternalIntegrationDtos.AiPromptRequest;
import com.enterprise.mm.payload.ExternalIntegrationDtos.AiPromptResponse;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationArchivedDocumentDto;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationCallbackRequest;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationCallbackResponse;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationCancelRequest;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationDocumentListDto;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationDocumentStatusDto;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationDocumentSummaryDto;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationDownloadResult;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationHealthDto;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationProviderRequest;
import com.enterprise.mm.payload.IntegrationEventResponse;
import com.enterprise.mm.payload.IntegrationSettingResponse;
import com.enterprise.mm.payload.IntegrationSettingUpdateRequest;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.IntegrationSettingMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.enums.IntegrationEventStatus;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.IntegrationSetting;
import com.enterprise.mm.config.IntegrationProperties;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.IntegrationSettingRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IntegrationSettingService {
    private static final List<ExternalIntegrationSystem> SUPPORTED_SETTINGS = List.of(
        ExternalIntegrationSystem.FI,
        ExternalIntegrationSystem.TREASURY,
        ExternalIntegrationSystem.BUDGETING,
        ExternalIntegrationSystem.PRODUCTION,
        ExternalIntegrationSystem.HR,
        ExternalIntegrationSystem.EDO,
        ExternalIntegrationSystem.E_IMZO,
        ExternalIntegrationSystem.ETTN,
        ExternalIntegrationSystem.EXCHANGE,
        ExternalIntegrationSystem.WMS,
        ExternalIntegrationSystem.SCADA,
        ExternalIntegrationSystem.LIMS,
        ExternalIntegrationSystem.ONE_C,
        ExternalIntegrationSystem.SOLIQ,
        ExternalIntegrationSystem.DIDOX,
        ExternalIntegrationSystem.FAKTURA_UZ,
        ExternalIntegrationSystem.KIMYOSANOAT,
        ExternalIntegrationSystem.ERP,
        ExternalIntegrationSystem.CHATGPT,
        ExternalIntegrationSystem.CLAUDE,
        ExternalIntegrationSystem.GEMINI
    );
    private static final List<ExternalIntegrationSystem> PROVIDER_SETTINGS = List.of(
        ExternalIntegrationSystem.SOLIQ,
        ExternalIntegrationSystem.DIDOX,
        ExternalIntegrationSystem.FAKTURA_UZ,
        ExternalIntegrationSystem.KIMYOSANOAT,
        ExternalIntegrationSystem.CHATGPT,
        ExternalIntegrationSystem.CLAUDE,
        ExternalIntegrationSystem.GEMINI
    );
    private final IntegrationSettingRepository repository;
    private final IntegrationSettingMapper mapper;
    private final IntegrationProperties properties;
    private final IntegrationEventRepository eventRepository;
    private final IntegrationEventService eventService;
    private final AttachmentService attachmentService;
    private final ReferenceMappingService referenceMappingService;
    private final SecretCipher secretCipher;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private com.enterprise.mm.erp.OneCTransport oneCTransport;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @org.springframework.beans.factory.annotation.Autowired
    private com.enterprise.mm.treasury.TreasuryNativeAccess treasuryNativeAccess;

    @Transactional(readOnly = true)
    public List<IntegrationSettingResponse> listSettings() {
        return listSettings(null, null, null);
    }

    @Transactional(readOnly = true)
    public List<IntegrationSettingResponse> listSettings(String search, LocalDate dateFrom, LocalDate dateTo) {
        return PROVIDER_SETTINGS.stream()
            .map(this::findOrDefault)
            .map(mapper::toResponse)
            .filter(item -> matchesSearch(item, search))
            .filter(item -> matchesDate(item, dateFrom, dateTo))
            .toList();
    }
    private boolean matchesSearch(IntegrationSettingResponse item, String search) {
        if (search == null || search.isBlank()) {
            return true;
        }
        String needle = search.trim().toLowerCase(Locale.ROOT);
        return contains(item.getSystemCode(), needle)
            || contains(item.getProviderName(), needle)
            || contains(item.getName(), needle)
            || contains(item.getCode(), needle)
            || contains(item.getStatus(), needle)
            || contains(item.getDescription(), needle)
            || contains(item.getBaseUrl(), needle)
            || contains(item.getAuthType(), needle)
            || contains(item.getProviderVersion(), needle)
            || contains(item.getOrganizationId(), needle)
            || contains(item.getProjectId(), needle)
            || contains(item.getAiModel(), needle);
    }

    private boolean matchesDate(IntegrationSettingResponse item, LocalDate dateFrom, LocalDate dateTo) {
        if (dateFrom == null && dateTo == null) {
            return true;
        }
        if (item.getCreatedAt() == null) {
            return false;
        }
        LocalDate createdDate = item.getCreatedAt().toLocalDate();
        return (dateFrom == null || !createdDate.isBefore(dateFrom)) && (dateTo == null || !createdDate.isAfter(dateTo));
    }

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    @Transactional(readOnly = true)
    public IntegrationSettingResponse getSetting(ExternalIntegrationSystem system) {
        requireSupported(system);
        return mapper.toResponse(findOrDefault(system));
    }
    @Transactional
    public IntegrationSettingResponse saveSetting(ExternalIntegrationSystem system, IntegrationSettingUpdateRequest dto) {
        requireSupported(system);
        IntegrationSetting setting = repository.findByTenantIdAndCompanyIdAndSystemCodeAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), system.name())
            .orElseGet(() -> defaultSetting(system));
        apply(system, dto, setting);
        IntegrationSetting saved = repository.save(setting);
        auditService.log("IntegrationSetting", saved.getId(), "UPDATE", saved.getSystemCode(), null, null);
        return mapper.toResponse(saved);
    }
    @Transactional(readOnly = true)
    public RuntimeEndpoint endpoint(ExternalIntegrationSystem system) {
        IntegrationSetting setting = repository.findByTenantIdAndCompanyIdAndSystemCodeAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), system.name())
            .orElse(null);
        if (setting != null) {
            RuntimeEndpoint saved = RuntimeEndpoint.from(setting, secretCipher);
            if (system == ExternalIntegrationSystem.FAKTURA_UZ && !saved.hasFakturaCredentials()) {
                RuntimeEndpoint configured = RuntimeEndpoint.from(system, properties.endpoint(system));
                if (configured.hasFakturaCredentials()) return saved.withFakturaCredentials(configured);
            }
            return saved;
        }
        IntegrationProperties.SystemEndpoint configured = properties.endpoint(system);
        return RuntimeEndpoint.from(system, configured);
    }
    @Transactional(readOnly = true)
    public IntegrationHealthDto health(ExternalIntegrationSystem system) {
        RuntimeEndpoint endpoint = endpoint(system);
        if(system==ExternalIntegrationSystem.TREASURY && treasuryNativeAccess!=null && treasuryNativeAccess.nativeEnabled())
            return new IntegrationHealthDto(system.name(),true,true,false,"TREASURY_NATIVE_INITIATED",
                "Treasury initiates all exchange; native worker polling confirms delivery. Outbound probes are disabled.",LocalDateTime.now());
        if(system==ExternalIntegrationSystem.ONE_C && oneCTransport!=null && oneCTransport.pullEnabled()) {
            return new IntegrationHealthDto(system.name(),endpoint.enabled(),true,false,"ONE_C_INITIATED",
                "Native 1C initiates exchange. Connection status is confirmed by native polling; no outbound health probe is sent.",LocalDateTime.now());
        }
        boolean configured = endpoint.configured();
        String status = status(endpoint, configured);
        String message;
        if (!endpoint.enabled()) {
            message = "Adapter disabled";
        } else if (!configured) {
            message = "Mock/outbox adapter is ready; set base URL for live API";
        } else if (endpoint.mockMode()) {
            message = "API URL configured, mock mode enabled";
        } else {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint.uri(endpoint.healthPath())))
                    .timeout(Duration.ofSeconds(8))
                    .GET();
                endpoint.headers().forEach(builder::header);
                HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                status = response.statusCode() >= 200 && response.statusCode() < 300 ? "LIVE_OK" : "LIVE_ERROR";
                message = "Health endpoint returned HTTP " + response.statusCode();
            } catch (Exception ex) {
                status = "LIVE_ERROR";
                message = ex.getMessage();
            }
        }
        return new IntegrationHealthDto(system.name(), endpoint.enabled(), configured, endpoint.mockMode(), status, message, LocalDateTime.now());
    }
    @Transactional(readOnly = true)
    public AiPromptResponse testAiProvider(ExternalIntegrationSystem system, AiPromptRequest request) {
        requireSupported(system);
        if (!isAiSystem(system)) {
            throw new BusinessException("AI test is available only for ChatGPT, Claude and Gemini");
        }
        RuntimeEndpoint endpoint = endpoint(system);
        String prompt = firstNonBlank(request == null ? null : request.prompt(), "Ответь коротко: ERP MM AI integration is ready.");
        if (!live(endpoint)) {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("mode", endpoint.mockMode() ? "MOCK" : "CONFIG_ONLY");
            raw.put("requestPath", aiRequestPath(system, endpoint));
            raw.put("context", request == null ? null : request.context());
            return new AiPromptResponse(
                system.name(),
                endpoint.providerName(),
                endpoint.aiModel(),
                "MOCK_OK",
                "AI provider is configured in mock mode; live request was not sent",
                "Mock response from " + endpoint.providerName() + ": настройки доступны, ключи не раскрываются.",
                raw,
                LocalDateTime.now()
            );
        }
        try {
            String path = aiRequestPath(system, endpoint);
            ObjectNode payload = aiPayload(system, endpoint, prompt, request == null ? null : request.context());
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint.uri(path)))
                .timeout(Duration.ofSeconds(timeout(endpoint)))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
            endpoint.headers().forEach(builder::header);
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode raw = readBody(response.body());
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            return new AiPromptResponse(
                system.name(),
                endpoint.providerName(),
                endpoint.aiModel(),
                ok ? "LIVE_OK" : "LIVE_ERROR",
                "Provider returned HTTP " + response.statusCode(),
                ok ? aiText(system, raw) : null,
                raw,
                LocalDateTime.now()
            );
        } catch (Exception ex) {
            return new AiPromptResponse(system.name(), endpoint.providerName(), endpoint.aiModel(), "LIVE_ERROR", ex.getMessage(), null, null, LocalDateTime.now());
        }
    }
    @Transactional
    public IntegrationEventResponse enqueueAction(ExternalIntegrationSystem system, String action, IntegrationProviderRequest request, String headerIdempotencyKey) {
        requireSupported(system);
        if(system==ExternalIntegrationSystem.TREASURY&&treasuryNativeAccess!=null&&treasuryNativeAccess.nativeEnabled())
            throw new BusinessException("Use the mapped contract payment native Treasury queue; legacy Treasury actions are disabled");
        RuntimeEndpoint endpoint = endpoint(system);
        String normalizedAction = normalizeAction(action, request == null ? null : request.action());
        String eventType = system.name() + "." + normalizedAction;
        com.enterprise.mm.payload.IntegrationEventCreateRequest event = new com.enterprise.mm.payload.IntegrationEventCreateRequest();
        event.setCode(eventType + "-" + UUID.randomUUID().toString().substring(0, 8));
        event.setName(system.displayName() + " " + normalizedAction);
        event.setStatus(IntegrationEventStatus.NEW.name());
        event.setEventType(eventType);
        event.setAggregateType(system.name());
        event.setPayload(envelope(system, endpoint, normalizedAction, request == null ? null : request.payload()));
        return eventService.createIdempotent(event, firstNonBlank(headerIdempotencyKey, request == null ? null : request.idempotencyKey()));
    }
    @Transactional(readOnly = true)
    public IntegrationDocumentStatusDto documentStatus(ExternalIntegrationSystem system, String externalId) {
        requireSupported(system);
        requireExternalId(externalId);
        RuntimeEndpoint endpoint = endpoint(system);
        if (!live(endpoint)) {
            return new IntegrationDocumentStatusDto(
                system.name(),
                externalId,
                "MOCK",
                "MOCK_ACCEPTED",
                "Mock/outbox mode: provider status is not requested",
                Map.of("externalId", externalId),
                LocalDateTime.now()
            );
        }
        try {
            HttpResponse<String> response = getText(endpoint, documentPath(endpoint, externalId, "/status"));
            JsonNode body = readBody(response.body());
            String providerStatus = firstNonBlank(text(body, "status"), text(body, "state"));
            return new IntegrationDocumentStatusDto(
                system.name(),
                externalId,
                response.statusCode() >= 200 && response.statusCode() < 300 ? "OK" : "ERROR",
                providerStatus == null ? "UNKNOWN" : providerStatus,
                "Provider returned HTTP " + response.statusCode(),
                body,
                LocalDateTime.now()
            );
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("Cannot request provider document status: " + ex.getMessage());
        }
    }
    @Transactional
    public IntegrationEventResponse cancelDocument(ExternalIntegrationSystem system, String externalId, IntegrationCancelRequest request, String headerIdempotencyKey) {
        requireSupported(system);
        requireExternalId(externalId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("externalId", externalId);
        payload.put("reason", request == null ? null : request.reason());
        payload.put("payload", request == null ? null : request.payload());
        return enqueueAction(
            system,
            "CANCEL_DOCUMENT",
            new IntegrationProviderRequest("CANCEL_DOCUMENT", payload, request == null ? null : request.idempotencyKey()),
            headerIdempotencyKey
        );
    }
    @Transactional(readOnly = true)
    public IntegrationDocumentListDto documents(ExternalIntegrationSystem system, String direction, LocalDate dateFrom, LocalDate dateTo, String status) {
        requireSupported(system);
        String normalizedDirection = normalizeDirection(direction);
        RuntimeEndpoint endpoint = endpoint(system);
        if (!live(endpoint)) {
            return mockDocuments(system, normalizedDirection);
        }
        try {
            String path = endpoint.documentPath() + "/" + normalizedDirection.toLowerCase(Locale.ROOT) + query(dateFrom, dateTo, status);
            HttpResponse<String> response = getText(endpoint, path);
            JsonNode body = readBody(response.body());
            return new IntegrationDocumentListDto(
                system.name(),
                normalizedDirection,
                summaries(body),
                body,
                LocalDateTime.now()
            );
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("Cannot request provider documents: " + ex.getMessage());
        }
    }
    @Transactional
    public IntegrationDownloadResult downloadDocument(ExternalIntegrationSystem system, String externalId) {
        requireSupported(system);
        requireExternalId(externalId);
        ProviderDocument document = fetchProviderDocument(system, externalId);
        AttachmentDto attachment = archiveOriginal(system, externalId, document);
        return new IntegrationDownloadResult(document.fileName(), document.contentType(), document.content(), attachment);
    }
    @Transactional
    public IntegrationArchivedDocumentDto archiveOriginalDocument(ExternalIntegrationSystem system, String externalId) {
        requireSupported(system);
        requireExternalId(externalId);
        ProviderDocument document = fetchProviderDocument(system, externalId);
        AttachmentDto attachment = archiveOriginal(system, externalId, document);
        return new IntegrationArchivedDocumentDto(system.name(), externalId, integrationDocumentEntityId(system, externalId), attachment, LocalDateTime.now());
    }
    @Transactional(readOnly = true)
    public List<AttachmentDto> originalDocuments(ExternalIntegrationSystem system, String externalId) {
        requireSupported(system);
        requireExternalId(externalId);
        return attachmentService.list("INTEGRATION_DOCUMENT", integrationDocumentEntityId(system, externalId));
    }
    private ProviderDocument fetchProviderDocument(ExternalIntegrationSystem system, String externalId) {
        RuntimeEndpoint endpoint = endpoint(system);
        if (!live(endpoint)) {
            return mockProviderDocument(system, externalId);
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint.uri(documentPath(endpoint, externalId, "/download"))))
                .timeout(Duration.ofSeconds(20))
                .GET();
            endpoint.headers().forEach(builder::header);
            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException("Provider download returned HTTP " + response.statusCode());
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
            String fileName = response.headers().firstValue("Content-Disposition")
                .map(this::fileNameFromDisposition)
                .orElse(system.name().toLowerCase(Locale.ROOT) + "-" + externalId + ".bin");
            return new ProviderDocument(fileName, contentType, response.body());
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("Cannot download provider document: " + ex.getMessage());
        }
    }
    private ProviderDocument mockProviderDocument(ExternalIntegrationSystem system, String externalId) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(Map.of(
                "system", system.name(),
                "externalId", externalId,
                "status", "MOCK_DOCUMENT"
            ));
            return new ProviderDocument(system.name().toLowerCase(Locale.ROOT) + "-" + externalId + ".json", "application/json", bytes);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("Cannot serialize mock document");
        }
    }
    private AttachmentDto archiveOriginal(ExternalIntegrationSystem system, String externalId, ProviderDocument document) {
        return attachmentService.saveBytes(
            "INTEGRATION_DOCUMENT",
            integrationDocumentEntityId(system, externalId),
            "PROVIDER_ORIGINAL_DOCUMENT",
            providerOriginalFileName(system, externalId, document.fileName()),
            document.contentType(),
            document.content()
        );
    }
    private UUID integrationDocumentEntityId(ExternalIntegrationSystem system, String externalId) {
        String key = TenantContext.currentTenant() + ":" + CompanyContext.currentCompany() + ":" + system.name() + ":" + externalId;
        return UUID.nameUUIDFromBytes(("INTEGRATION_DOCUMENT:" + key).getBytes(StandardCharsets.UTF_8));
    }
    private String providerOriginalFileName(ExternalIntegrationSystem system, String externalId, String providerFileName) {
        String fallback = system.name().toLowerCase(Locale.ROOT) + "-" + externalId + ".bin";
        String fileName = hasText(providerFileName) ? providerFileName : fallback;
        return system.name().toLowerCase(Locale.ROOT) + "-" + externalId + "-original-" + fileName;
    }
    @Transactional
    public IntegrationCallbackResponse acceptCallback(ExternalIntegrationSystem system, IntegrationCallbackRequest request, String headerIdempotencyKey) {
        if(system==ExternalIntegrationSystem.TREASURY&&treasuryNativeAccess!=null&&treasuryNativeAccess.nativeEnabled())
            throw new BusinessException("Legacy Treasury callbacks are disabled; use the scoped native Treasury evidence API");
        if (system == ExternalIntegrationSystem.TREASURY) {
            requireCorrelatedTreasuryCallback(request, headerIdempotencyKey);
        }
        String status = request == null || !hasText(request.status()) ? "RECEIVED" : normalizeAction(request.status(), null);
        String defaultCallbackType = system == ExternalIntegrationSystem.TREASURY && isSuccessfulPaymentStatus(status)
            ? "PAYMENT_STATUS_" + status
            : "CALLBACK_" + status;
        // Treasury callbacks are server-classified. A caller cannot promote an
        // arbitrary eventType into successful payment evidence.
        String callbackType = request == null || system == ExternalIntegrationSystem.TREASURY
            ? defaultCallbackType
            : firstNonBlank(request.eventType(), defaultCallbackType);
        String eventType = system.name() + "." + normalizeAction(callbackType, null);
        String externalId = request == null ? null : trim(request.externalId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", system.name());
        payload.put("externalId", externalId);
        payload.put("status", status);
        payload.put("payload", request == null ? null : request.payload());
        com.enterprise.mm.payload.IntegrationEventCreateRequest event = new com.enterprise.mm.payload.IntegrationEventCreateRequest();
        event.setCode(eventType + "-" + UUID.randomUUID().toString().substring(0, 8));
        event.setName(system.displayName() + " callback " + status);
        event.setStatus(IntegrationEventStatus.NEW.name());
        event.setEventType(eventType);
        event.setAggregateType(system.name());
        event.setAggregateId(request == null ? null : request.aggregateId());
        event.setExternalId(externalId);
        event.setDescription(status);
        event.setPayload(writePayload(payload));
        IntegrationEventResponse saved = eventService.recordInboundCallbackIdempotent(
            event,
            firstNonBlank(headerIdempotencyKey, request == null ? null : request.idempotencyKey())
        );
        return new IntegrationCallbackResponse(
            saved.getId(),
            system.name(),
            saved.getExternalId(),
            firstNonBlank(saved.getDescription(), status),
            "Callback accepted"
        );
    }

    private void requireCorrelatedTreasuryCallback(
        IntegrationCallbackRequest request,
        String headerIdempotencyKey
    ) {
        if (request == null || request.aggregateId() == null || !hasText(request.externalId()) || !hasText(request.status())) {
            throw new BadRequestException(
                "Treasury callback requires aggregateId, externalId and status"
            );
        }
        if (!hasText(firstNonBlank(headerIdempotencyKey, request.idempotencyKey()))) {
            throw new BadRequestException("Idempotency-Key is required for Treasury callbacks");
        }
        boolean paymentRequestExists = eventRepository
            .existsByTenantIdAndCompanyIdAndAggregateTypeAndAggregateIdAndEventTypeAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                ExternalIntegrationSystem.TREASURY.name(),
                request.aggregateId(),
                "TREASURY.PAYMENT_REQUEST"
            );
        if (!paymentRequestExists) {
            throw new BusinessException(
                "Treasury callback does not match an outgoing payment request"
            );
        }
    }

    private boolean isSuccessfulPaymentStatus(String status) {
        return Set.of("PAID", "SUCCESS", "SUCCEEDED", "COMPLETED").contains(status);
    }
    @Transactional(readOnly = true)
    public long pendingEvents(ExternalIntegrationSystem system) {
        return eventRepository.countByTenantIdAndCompanyIdAndAggregateTypeAndStatusInAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            system.name(),
            List.of(IntegrationEventStatus.NEW.name(), IntegrationEventStatus.RETRY.name())
        );
    }
    @Transactional(readOnly = true)
    public Optional<IntegrationEvent> lastEvent(ExternalIntegrationSystem system) {
        return eventRepository.findFirstByTenantIdAndCompanyIdAndAggregateTypeAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            system.name()
        );
    }
    private IntegrationSetting findOrDefault(ExternalIntegrationSystem system) {
        requireSupported(system);
        return repository.findByTenantIdAndCompanyIdAndSystemCodeAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), system.name())
            .orElseGet(() -> defaultSetting(system));
    }
    private IntegrationSetting defaultSetting(ExternalIntegrationSystem system) {
        IntegrationSetting setting = new IntegrationSetting();
        setting.setSystemCode(system.name());
        setting.setProviderName(system.displayName());
        setting.setCode("INT-" + system.name());
        setting.setName(system.displayName());
        setting.setStatus("ACTIVE");
        setting.setDescription(system.description());
        setting.setEnabled(true);
        setting.setMockMode(true);
        setting.setAuthType("BEARER");
        switch (system) {
            case FI -> {
                setting.setMockMode(false);
                setting.setAuthType("BASIC");
                setting.setBaseUrl("http://localhost:8082");
                setting.setHealthPath("/api/v1/health/status");
                setting.setEventsPath("/api/v1/basic/finmodel/integrations/entries");
                setting.setDocumentPath("/api/v1/basic/finmodel/integrations/entries");
                setting.setApiKeyHeader("Authorization");
            }
            case TREASURY -> {
                IntegrationProperties.SystemEndpoint configured = properties.endpoint(system);
                setting.setMockMode(configured.isMockMode());
                setting.setAuthType("BASIC");
                setting.setBaseUrl(firstNonBlank(configured.getBaseUrl(), "http://localhost:8082"));
                setting.setHealthPath("/api/v1/health/status");
                setting.setEventsPath("/api/v1/basic/treasury/payments/requests");
                setting.setDocumentPath("/api/v1/basic/treasury/payments/requests");
                setting.setApiKeyHeader("Authorization");
            }
            case BUDGETING -> {
                setting.setMockMode(false);
                setting.setAuthType("BASIC");
                setting.setBaseUrl("http://localhost:8082");
                setting.setHealthPath("/api/v1/health/status");
                setting.setEventsPath("/api/v1/procurement/commitments/save");
                setting.setDocumentPath("/api/v1/procurement/commitments/save");
                setting.setApiKeyHeader("Authorization");
            }
            case PRODUCTION -> {
                setting.setMockMode(false);
                setting.setAuthType("API_KEY");
                setting.setBaseUrl("http://localhost:8090");
                setting.setHealthPath("/actuator/health");
                setting.setEventsPath("/api/v1/integrations/erp/confirmed-production");
                setting.setDocumentPath("/api/v1/integrations/erp/confirmed-production");
                setting.setApiKeyHeader("X-Role");
                setting.setApiKeyEncrypted(secretCipher.encrypt("integration_service"));
            }
            case HR -> {
                setting.setMockMode(false);
                setting.setAuthType("API_KEY");
                setting.setBaseUrl("http://localhost:8091");
                setting.setHealthPath("/actuator/health");
                setting.setEventsPath("/api/data-exchange/jobs");
                setting.setDocumentPath("/api/data-exchange/jobs");
                setting.setApiKeyHeader("X-Service-Token");
            }
            case EDO -> {
                setting.setMockMode(false);
                setting.setAuthType("API_KEY");
                setting.setBaseUrl("http://localhost:8081");
                setting.setHealthPath("/actuator/health");
                setting.setEventsPath("/api/documents/outgoing");
                setting.setDocumentPath("/api/documents/outgoing");
                setting.setApiKeyHeader("X-Service-Token");
            }
            case E_IMZO -> {
                setting.setBaseUrl("http://localhost:18080");
                setting.setHealthPath("/health");
                setting.setEventsPath("/api/signature/verify");
                setting.setDocumentPath("/api/signature/verify");
                setting.setApiKeyHeader("Authorization");
            }
            case ETTN -> {
                setting.setMockMode(false);
                setting.setAuthType("API_KEY");
                setting.setBaseUrl("http://localhost:8081");
                setting.setHealthPath("/actuator/health");
                setting.setEventsPath("/api/documents/outgoing");
                setting.setDocumentPath("/api/documents/outgoing");
                setting.setApiKeyHeader("X-Service-Token");
            }
            case EXCHANGE -> {
                setting.setMockMode(false);
                setting.setAuthType("BASIC");
                setting.setBaseUrl("http://localhost:8082");
                setting.setHealthPath("/api/v1/health/status");
                setting.setEventsPath("/api/v1/basic/treasury/tenders");
                setting.setDocumentPath("/api/v1/basic/treasury/tenders");
                setting.setApiKeyHeader("Authorization");
            }
            case ERP -> {
                setting.setMockMode(false);
                setting.setAuthType("BEARER");
                setting.setBaseUrl("http://localhost:8080");
                setting.setHealthPath("/api/integration/health");
                setting.setEventsPath("/api/integration/envelopes");
                setting.setDocumentPath("/api/integration/envelopes");
                setting.setApiKeyHeader("Authorization");
            }
            case WMS -> {
                setting.setMockMode(false);
                setting.setAuthType("API_KEY");
                setting.setBaseUrl("http://localhost:8090");
                setting.setHealthPath("/actuator/health");
                setting.setEventsPath("/api/v1/integrations/wms/transfers/confirm");
                setting.setDocumentPath("/api/v1/integrations/wms/transfers/receipts/import");
                setting.setApiKeyHeader("X-Role");
                setting.setApiKeyEncrypted(secretCipher.encrypt("integration_service"));
            }
            case SCADA -> {
                setting.setMockMode(false);
                setting.setAuthType("API_KEY");
                setting.setBaseUrl("http://localhost:8090");
                setting.setHealthPath("/actuator/health");
                setting.setEventsPath("/api/v1/integrations/scada/events/import");
                setting.setDocumentPath("/api/v1/integrations/scada/events/import");
                setting.setApiKeyHeader("X-Role");
                setting.setApiKeyEncrypted(secretCipher.encrypt("integration_service"));
            }
            case LIMS -> {
                setting.setMockMode(false);
                setting.setAuthType("API_KEY");
                setting.setBaseUrl("http://localhost:8081");
                setting.setHealthPath("/api/v1/health");
                setting.setEventsPath("/api/v1/samples");
                setting.setDocumentPath("/api/v1/samples");
                setting.setApiKeyHeader("X-User-Email");
                setting.setApiKeyEncrypted(secretCipher.encrypt("admin@lims.uz"));
            }
            case ONE_C -> {
                setting.setMockMode(false);
                setting.setAuthType("BASIC");
                setting.setBaseUrl("http://localhost:8082");
                setting.setHealthPath("/api/v1/health/status");
                setting.setEventsPath("/api/v1/basic/finmodel/integrations/entries");
                setting.setDocumentPath("/api/v1/basic/finmodel/integrations/entries");
                setting.setApiKeyHeader("Authorization");
            }
            case SOLIQ -> {
                setting.setBaseUrl("https://api.soliq-servis.uz");
                setting.setAuthPath("/api/auth/login");
                setting.setHealthPath("/health");
                setting.setEventsPath("/events");
                setting.setInvoicePath("/api/invoice");
                setting.setDocumentPath("/api/documents");
                setting.setSupplierStatusPath("/api/taxpayer/status");
                setting.setApiKeyHeader("Authorization");
            }
            case DIDOX -> {
                setting.setBaseUrl("https://api.didox.uz");
                setting.setAuthPath("/v1/auth/login");
                setting.setHealthPath("/health");
                setting.setEventsPath("/v1/events");
                setting.setInvoicePath("/v1/invoices");
                setting.setDocumentPath("/v1/documents");
                setting.setSupplierStatusPath("/v1/companies/status");
                setting.setApiKeyHeader("Authorization");
            }
            case FAKTURA_UZ -> {
                setting.setAuthType("OAUTH2");
                setting.setBaseUrl("https://api.faktura.uz");
                setting.setAuthPath("https://account.faktura.uz/token");
                setting.setHealthPath("/Api/account/getuserdetails");
                setting.setEventsPath("/Api/Document/GetDocuments");
                setting.setInvoicePath("/Api/Document/GetDocumentsContent");
                setting.setDocumentPath("/Api/Document/GetDocuments");
                setting.setSupplierStatusPath("/Api/Company/GetCompany");
                setting.setApiKeyHeader("Authorization");
            }
            case KIMYOSANOAT -> {
                setting.setBaseUrl("https://api-navoiy-azot.uzkimyo.uz/be/api/v1");
                setting.setAuthType("BASIC");
            }
            case CHATGPT -> {
                setting.setBaseUrl("https://api.openai.com");
                setting.setAuthType("BEARER");
                setting.setHealthPath("/v1/models");
                setting.setEventsPath("/v1/chat/completions");
                setting.setAiRequestPath("/v1/chat/completions");
                setting.setAiModel("gpt-4.1-mini");
                setting.setAiSystemPrompt(defaultAiSystemPrompt());
                setting.setAiTemperature(BigDecimal.valueOf(0.20));
                setting.setAiMaxTokens(1024);
                setting.setAiTimeoutSeconds(30);
                setting.setApiKeyHeader("Authorization");
            }
            case CLAUDE -> {
                setting.setBaseUrl("https://api.anthropic.com");
                setting.setAuthType("API_KEY");
                setting.setHealthPath("/v1/messages");
                setting.setEventsPath("/v1/messages");
                setting.setAiRequestPath("/v1/messages");
                setting.setAiModel("claude-3-5-sonnet-latest");
                setting.setProviderVersion("2023-06-01");
                setting.setAiSystemPrompt(defaultAiSystemPrompt());
                setting.setAiTemperature(BigDecimal.valueOf(0.20));
                setting.setAiMaxTokens(1024);
                setting.setAiTimeoutSeconds(30);
                setting.setApiKeyHeader("x-api-key");
            }
            case GEMINI -> {
                setting.setBaseUrl("https://generativelanguage.googleapis.com");
                setting.setAuthType("API_KEY");
                setting.setHealthPath("/v1beta/models");
                setting.setEventsPath("/v1beta/models/{model}:generateContent");
                setting.setAiRequestPath("/v1beta/models/{model}:generateContent");
                setting.setAiModel("gemini-1.5-pro");
                setting.setAiSystemPrompt(defaultAiSystemPrompt());
                setting.setAiTemperature(BigDecimal.valueOf(0.20));
                setting.setAiMaxTokens(1024);
                setting.setAiTimeoutSeconds(30);
                setting.setApiKeyHeader("x-goog-api-key");
            }
            default -> throw new BusinessException("Unsupported integration setting: " + system.name());
        }
        return setting;
    }
    private void apply(ExternalIntegrationSystem system, IntegrationSettingUpdateRequest dto, IntegrationSetting setting) {
        setting.setSystemCode(system.name());
        setting.setProviderName(blankToDefault(dto.getProviderName(), setting.getProviderName()));
        setting.setName(blankToDefault(dto.getName(), setting.getProviderName()));
        setting.setCode(blankToDefault(dto.getCode(), "INT-" + setting.getSystemCode()));
        setting.setStatus(blankToDefault(dto.getStatus(), "ACTIVE"));
        setting.setDescription(dto.getDescription());
        setting.setEnabled(dto.isEnabled());
        setting.setMockMode(dto.isMockMode());
        setting.setBaseUrl(trim(dto.getBaseUrl()));
        setting.setAuthType(normalizeAuthType(dto.getAuthType()));
        setting.setUsername(trim(dto.getUsername()));
        setting.setApiKeyHeader(blankToDefault(dto.getApiKeyHeader(), "Authorization"));
        setting.setClientId(trim(dto.getClientId()));
        setting.setSenderTin(trim(dto.getSenderTin()));
        setting.setSenderBranchCode(trim(dto.getSenderBranchCode()));
        setting.setCallbackUrl(trim(dto.getCallbackUrl()));
        setting.setAuthPath(path(dto.getAuthPath(), setting.getAuthPath()));
        setting.setHealthPath(path(dto.getHealthPath(), setting.getHealthPath()));
        setting.setEventsPath(path(dto.getEventsPath(), setting.getEventsPath()));
        setting.setInvoicePath(path(dto.getInvoicePath(), setting.getInvoicePath()));
        setting.setDocumentPath(path(dto.getDocumentPath(), setting.getDocumentPath()));
        setting.setSupplierStatusPath(path(dto.getSupplierStatusPath(), setting.getSupplierStatusPath()));
        if (isAiSystem(system)) {
            setting.setProviderVersion(trim(dto.getProviderVersion()));
            setting.setOrganizationId(trim(dto.getOrganizationId()));
            setting.setProjectId(trim(dto.getProjectId()));
            setting.setAiModel(blankToDefault(dto.getAiModel(), setting.getAiModel()));
            setting.setAiRequestPath(path(dto.getAiRequestPath(), setting.getAiRequestPath()));
            setting.setAiSystemPrompt(blankToDefault(dto.getAiSystemPrompt(), defaultAiSystemPrompt()));
            setting.setAiTemperature(dto.getAiTemperature() == null ? setting.getAiTemperature() : dto.getAiTemperature());
            setting.setAiMaxTokens(dto.getAiMaxTokens() == null ? setting.getAiMaxTokens() : Math.max(1, dto.getAiMaxTokens()));
            setting.setAiTimeoutSeconds(dto.getAiTimeoutSeconds() == null ? setting.getAiTimeoutSeconds() : Math.max(1, dto.getAiTimeoutSeconds()));
        }
        if (hasText(dto.getPassword())) {
            setting.setPasswordEncrypted(secretCipher.encrypt(dto.getPassword()));
        }
        if (hasText(dto.getBearerToken())) {
            setting.setBearerTokenEncrypted(secretCipher.encrypt(dto.getBearerToken()));
        }
        if (hasText(dto.getApiKey())) {
            setting.setApiKeyEncrypted(secretCipher.encrypt(dto.getApiKey()));
        }
        if (hasText(dto.getClientSecret())) {
            setting.setClientSecretEncrypted(secretCipher.encrypt(dto.getClientSecret()));
        }
    }
    private String envelope(ExternalIntegrationSystem system, RuntimeEndpoint endpoint, String action, Object payload) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("source", "MM");
        envelope.put("targetSystem", system.name());
        envelope.put("provider", endpoint.providerName());
        envelope.put("action", action);
        envelope.put("targetPath", targetPath(endpoint, action));
        envelope.put("createdAt", LocalDateTime.now().toString());
        JsonNode payloadJson = payloadNode(payload);
        if (payloadJson instanceof ObjectNode payloadObject) {
            payloadObject.set("mappedReferences", objectMapper.valueToTree(referenceMappingService.mappedReferences(payloadJson, system.name(), integrationAccountingArea(system))));
        }
        envelope.set("payload", payloadJson);
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("Cannot serialize integration payload");
        }
    }
    private String integrationAccountingArea(ExternalIntegrationSystem system) {
        return switch (system) {
            case SOLIQ, FAKTURA_UZ -> "TAX";
            case DIDOX -> "PROCUREMENT";
            case CHATGPT, CLAUDE, GEMINI -> "MANAGEMENT";
            case FI, ONE_C -> "FINANCIAL";
            case WMS -> "WAREHOUSE";
            case LIMS -> "QUALITY";
            case HR -> "HR";
            case BUDGETING -> "BUDGET";
            default -> "MANAGEMENT";
        };
    }
    private JsonNode payloadNode(Object payload) {
        if (payload == null) {
            return objectMapper.createObjectNode();
        }
        if (payload instanceof JsonNode jsonNode) {
            return jsonNode;
        }
        if (payload instanceof String value) {
            if (value.isBlank()) {
                return objectMapper.createObjectNode();
            }
            try {
                return objectMapper.readTree(value);
            } catch (JsonProcessingException ex) {
                return objectMapper.createObjectNode().put("rawPayload", value);
            }
        }
        return objectMapper.valueToTree(payload);
    }
    private ObjectNode aiPayload(ExternalIntegrationSystem system, RuntimeEndpoint endpoint, String prompt, Object context) {
        return switch (system) {
            case CHATGPT -> chatGptPayload(endpoint, prompt, context);
            case CLAUDE -> claudePayload(endpoint, prompt, context);
            case GEMINI -> geminiPayload(endpoint, prompt, context);
            default -> throw new BusinessException("Unsupported AI provider: " + system.name());
        };
    }
    private ObjectNode chatGptPayload(RuntimeEndpoint endpoint, String prompt, Object context) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", endpoint.aiModel());
        root.put("temperature", temperature(endpoint));
        root.put("max_tokens", maxTokens(endpoint));
        var messages = objectMapper.createArrayNode();
        messages.add(objectMapper.createObjectNode().put("role", "system").put("content", aiSystemPrompt(endpoint)));
        messages.add(objectMapper.createObjectNode().put("role", "user").put("content", promptWithContext(prompt, context)));
        root.set("messages", messages);
        return root;
    }
    private ObjectNode claudePayload(RuntimeEndpoint endpoint, String prompt, Object context) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", endpoint.aiModel());
        root.put("system", aiSystemPrompt(endpoint));
        root.put("temperature", temperature(endpoint));
        root.put("max_tokens", maxTokens(endpoint));
        var messages = objectMapper.createArrayNode();
        messages.add(objectMapper.createObjectNode().put("role", "user").put("content", promptWithContext(prompt, context)));
        root.set("messages", messages);
        return root;
    }
    private ObjectNode geminiPayload(RuntimeEndpoint endpoint, String prompt, Object context) {
        ObjectNode root = objectMapper.createObjectNode();
        var contents = objectMapper.createArrayNode();
        var parts = objectMapper.createArrayNode();
        parts.add(objectMapper.createObjectNode().put("text", aiSystemPrompt(endpoint) + "\n\n" + promptWithContext(prompt, context)));
        ObjectNode content = objectMapper.createObjectNode();
        content.put("role", "user");
        content.set("parts", parts);
        contents.add(content);
        ObjectNode generationConfig = objectMapper.createObjectNode();
        generationConfig.put("temperature", temperature(endpoint));
        generationConfig.put("maxOutputTokens", maxTokens(endpoint));
        root.set("contents", contents);
        root.set("generationConfig", generationConfig);
        return root;
    }
    private String aiText(ExternalIntegrationSystem system, JsonNode raw) {
        return switch (system) {
            case CHATGPT -> text(raw.path("choices").path(0).path("message"), "content");
            case CLAUDE -> text(raw.path("content").path(0), "text");
            case GEMINI -> text(raw.path("candidates").path(0).path("content").path("parts").path(0), "text");
            default -> null;
        };
    }
    private String aiRequestPath(ExternalIntegrationSystem system, RuntimeEndpoint endpoint) {
        String path = firstNonBlank(endpoint.aiRequestPath(), endpoint.eventsPath());
        if (!hasText(path)) {
            path = switch (system) {
                case CHATGPT -> "/v1/chat/completions";
                case CLAUDE -> "/v1/messages";
                case GEMINI -> "/v1beta/models/{model}:generateContent";
                default -> "/events";
            };
        }
        return path.replace("{model}", enc(endpoint.aiModel()));
    }
    private String promptWithContext(String prompt, Object context) {
        if (context == null) {
            return prompt;
        }
        try {
            return prompt + "\n\nКонтекст JSON:\n" + objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException ex) {
            return prompt;
        }
    }
    private String aiSystemPrompt(RuntimeEndpoint endpoint) {
        return firstNonBlank(endpoint.aiSystemPrompt(), defaultAiSystemPrompt());
    }
    private String defaultAiSystemPrompt() {
        return "Ты корпоративный AI-ассистент ERP MM. Отвечай кратко, не раскрывай секреты, не меняй данные без явной команды.";
    }
    private BigDecimal temperature(RuntimeEndpoint endpoint) {
        return endpoint.aiTemperature() == null ? BigDecimal.valueOf(0.20) : endpoint.aiTemperature();
    }
    private int maxTokens(RuntimeEndpoint endpoint) {
        return endpoint.aiMaxTokens() == null || endpoint.aiMaxTokens() < 1 ? 1024 : endpoint.aiMaxTokens();
    }
    private int timeout(RuntimeEndpoint endpoint) {
        return endpoint.aiTimeoutSeconds() == null || endpoint.aiTimeoutSeconds() < 1 ? 30 : endpoint.aiTimeoutSeconds();
    }
    private String targetPath(RuntimeEndpoint endpoint, String action) {
        return switch (action) {
            case "TAX_INVOICE", "SEND_TAX_INVOICE", "INVOICE", "IMPORT_INVOICE" -> endpoint.invoicePath();
            case "DOCUMENT", "SEND_DOCUMENT", "IMPORT_DOCUMENT", "DOCUMENT_REGISTER", "CANCEL_DOCUMENT", "DOCUMENT_STATUS", "INCOMING_DOCUMENTS", "OUTGOING_DOCUMENTS", "DOWNLOAD_DOCUMENT" -> endpoint.documentPath();
            case "SUPPLIER_STATUS", "SUPPLIER_TAX_STATUS", "COMPANY_STATUS", "TAXPAYER_STATUS" -> endpoint.supplierStatusPath();
            default -> endpoint.eventsPath();
        };
    }
    private String normalizeAction(String pathAction, String bodyAction) {
        String raw = firstNonBlank(bodyAction, pathAction);
        if (!hasText(raw)) {
            throw new BusinessException("action is required");
        }
        return raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
    private String normalizeAuthType(String authType) {
        String normalized = blankToDefault(authType, "NONE").trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "NONE", "BASIC", "BEARER", "API_KEY", "OAUTH2" -> normalized;
            default -> throw new BusinessException("Unsupported authType: " + authType);
        };
    }
    private String status(RuntimeEndpoint endpoint, boolean configured) {
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
    private void requireSupported(ExternalIntegrationSystem system) {
        if (!SUPPORTED_SETTINGS.contains(system)) {
            throw new BusinessException("Integration settings are available for all supported MM external systems");
        }
    }
    private boolean isAiSystem(ExternalIntegrationSystem system) {
        return system == ExternalIntegrationSystem.CHATGPT || system == ExternalIntegrationSystem.CLAUDE || system == ExternalIntegrationSystem.GEMINI;
    }
    private String path(String value, String fallback) {
        String safe = blankToDefault(value, fallback);
        if (!hasText(safe)) {
            return "/";
        }
        if (safe.startsWith("/https://") || safe.startsWith("/http://")) {
            safe = safe.substring(1);
        }
        if (safe.startsWith("https://") || safe.startsWith("http://")) {
            return safe;
        }
        return safe.startsWith("/") ? safe : "/" + safe;
    }
    private String firstNonBlank(String first, String second) {
        return hasText(first) ? first : (hasText(second) ? second : null);
    }
    private boolean live(RuntimeEndpoint endpoint) {
        requireOutboundAllowed(endpoint);
        return endpoint.enabled() && endpoint.configured() && !endpoint.mockMode();
    }
    private void requireExternalId(String externalId) {
        if (!hasText(externalId)) {
            throw new BusinessException("externalId is required");
        }
    }
    private void requireOutboundAllowed(RuntimeEndpoint endpoint) {
        if(treasuryNativeAccess!=null&&treasuryNativeAccess.nativeEnabled()&&ExternalIntegrationSystem.TREASURY.name().equals(endpoint.systemCode()))
            throw new BusinessException("Treasury initiates exchange; outgoing provider HTTP is disabled");
        if(oneCTransport!=null&&oneCTransport.pullEnabled()&&ExternalIntegrationSystem.ONE_C.name().equals(endpoint.systemCode()))
            throw new BusinessException("1C initiates exchange; direct provider document queries are disabled");
    }
    private HttpResponse<String> getText(RuntimeEndpoint endpoint, String path) throws Exception {
        requireOutboundAllowed(endpoint);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint.uri(path)))
            .timeout(Duration.ofSeconds(15))
            .GET();
        endpoint.headers().forEach(builder::header);
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BusinessException("Provider endpoint returned HTTP " + response.statusCode());
        }
        return response;
    }
    private JsonNode readBody(String body) throws JsonProcessingException {
        return body == null || body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
    }
    private String documentPath(RuntimeEndpoint endpoint, String externalId, String suffix) {
        String base = endpoint.documentPath() == null || endpoint.documentPath().isBlank() ? "/documents" : endpoint.documentPath();
        String cleanBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return cleanBase + "/" + enc(externalId) + suffix;
    }
    private String query(LocalDate dateFrom, LocalDate dateTo, String status) {
        List<String> params = new ArrayList<>();
        if (dateFrom != null) {
            params.add("dateFrom=" + enc(dateFrom.toString()));
        }
        if (dateTo != null) {
            params.add("dateTo=" + enc(dateTo.toString()));
        }
        if (hasText(status)) {
            params.add("status=" + enc(status));
        }
        return params.isEmpty() ? "" : "?" + String.join("&", params);
    }
    private String normalizeDirection(String direction) {
        String normalized = direction == null ? "OUTGOING" : direction.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "IN", "INCOMING" -> "INCOMING";
            case "OUT", "OUTGOING" -> "OUTGOING";
            default -> throw new BusinessException("Unsupported document direction: " + direction);
        };
    }
    private IntegrationDocumentListDto mockDocuments(ExternalIntegrationSystem system, String direction) {
        List<IntegrationDocumentSummaryDto> documents = eventRepository
            .findByTenantIdAndCompanyIdAndAggregateTypeAndIsDeletedFalseOrderByCreatedAtDesc(TenantContext.currentTenant(), CompanyContext.currentCompany(), system.name())
            .stream()
            .limit(50)
            .map(event -> new IntegrationDocumentSummaryDto(
                event.getId().toString(),
                event.getCode(),
                event.getCreatedAt() == null ? null : event.getCreatedAt().toLocalDate().toString(),
                event.getEventType(),
                event.getStatus(),
                payloadNode(event.getPayload())
            ))
            .toList();
        return new IntegrationDocumentListDto(system.name(), direction, documents, Map.of("mode", "MOCK_OUTBOX"), LocalDateTime.now());
    }
    private List<IntegrationDocumentSummaryDto> summaries(JsonNode body) {
        JsonNode rows = body;
        if (body.has("documents")) {
            rows = body.path("documents");
        } else if (body.has("items")) {
            rows = body.path("items");
        } else if (body.has("data")) {
            rows = body.path("data");
        }
        if (!rows.isArray()) {
            return List.of();
        }
        List<IntegrationDocumentSummaryDto> result = new ArrayList<>();
        rows.forEach(row -> result.add(new IntegrationDocumentSummaryDto(
            firstNonBlank(text(row, "externalId"), firstNonBlank(text(row, "id"), text(row, "documentId"))),
            firstNonBlank(text(row, "documentNumber"), firstNonBlank(text(row, "number"), text(row, "docNo"))),
            firstNonBlank(text(row, "documentDate"), firstNonBlank(text(row, "date"), text(row, "createdAt"))),
            firstNonBlank(text(row, "documentType"), firstNonBlank(text(row, "type"), text(row, "eventType"))),
            firstNonBlank(text(row, "status"), text(row, "state")),
            row
        )));
        return result;
    }
    private JsonNode payloadNode(String payload) {
        if (!hasText(payload)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(payload);
        } catch (JsonProcessingException ex) {
            return objectMapper.createObjectNode().put("rawPayload", payload);
        }
    }
    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
    private String writePayload(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("Cannot serialize integration payload");
        }
    }
    private String fileNameFromDisposition(String value) {
        String marker = "filename=";
        int index = value == null ? -1 : value.toLowerCase(Locale.ROOT).indexOf(marker);
        if (index < 0) {
            return "document.bin";
        }
        return value.substring(index + marker.length()).replace("\"", "").trim();
    }
    private String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
    private String blankToDefault(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }
    private String trim(String value) {
        return value == null ? null : value.trim();
    }
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
    public record RuntimeEndpoint(
        String systemCode,
        String providerName,
        boolean enabled,
        boolean mockMode,
        String baseUrl,
        String authType,
        String username,
        String password,
        String bearerToken,
        String apiKeyHeader,
	        String apiKey,
	        String clientId,
        String clientSecret,
        String authPath,
        String healthPath,
        String eventsPath,
        String invoicePath,
        String documentPath,
        String supplierStatusPath,
        String providerVersion,
        String organizationId,
        String projectId,
        String aiModel,
        String aiRequestPath,
        String aiSystemPrompt,
        BigDecimal aiTemperature,
        Integer aiMaxTokens,
        Integer aiTimeoutSeconds
    ) {
        boolean hasFakturaCredentials() {
            return username != null && !username.isBlank() && password != null && !password.isBlank()
                && clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
        }

        RuntimeEndpoint withFakturaCredentials(RuntimeEndpoint source) {
            return new RuntimeEndpoint(systemCode, providerName, enabled, mockMode, baseUrl, authType,
                source.username, source.password, bearerToken, apiKeyHeader, apiKey,
                source.clientId, source.clientSecret, authPath, healthPath, eventsPath, invoicePath,
                documentPath, supplierStatusPath, providerVersion, organizationId, projectId, aiModel,
                aiRequestPath, aiSystemPrompt, aiTemperature, aiMaxTokens, aiTimeoutSeconds);
        }

        static RuntimeEndpoint from(IntegrationSetting setting, SecretCipher cipher) {
            return new RuntimeEndpoint(
                setting.getSystemCode(),
                setting.getProviderName(),
                setting.isEnabled(),
                setting.isMockMode(),
                setting.getBaseUrl(),
                setting.getAuthType(),
                setting.getUsername(),
                cipher.decrypt(setting.getPasswordEncrypted()),
                cipher.decrypt(setting.getBearerTokenEncrypted()),
                setting.getApiKeyHeader(),
	                cipher.decrypt(setting.getApiKeyEncrypted()),
	                setting.getClientId(),
	                cipher.decrypt(setting.getClientSecretEncrypted()),
	                setting.getAuthPath(),
	                setting.getHealthPath(),
                setting.getEventsPath(),
                setting.getInvoicePath(),
                setting.getDocumentPath(),
                setting.getSupplierStatusPath(),
                setting.getProviderVersion(),
                setting.getOrganizationId(),
                setting.getProjectId(),
                setting.getAiModel(),
                setting.getAiRequestPath(),
                setting.getAiSystemPrompt(),
                setting.getAiTemperature(),
                setting.getAiMaxTokens(),
                setting.getAiTimeoutSeconds()
            );
        }
        static RuntimeEndpoint from(ExternalIntegrationSystem system, IntegrationProperties.SystemEndpoint endpoint) {
            return new RuntimeEndpoint(
                system.name(),
                system.displayName(),
                endpoint.isEnabled(),
                endpoint.isMockMode(),
                endpoint.getBaseUrl(),
                endpoint.getAuthType(),
                endpoint.getUsername(),
                endpoint.getPassword(),
                endpoint.getBearerToken(),
                endpoint.getApiKeyHeader(),
	                endpoint.getApiKey(),
	                endpoint.getClientId(),
	                endpoint.getClientSecret(),
	                endpoint.getAuthPath(),
	                endpoint.getHealthPath(),
	                endpoint.getEventsPath(),
                endpoint.getEventsPath(),
                endpoint.getEventsPath(),
                endpoint.getEventsPath(),
                null,
                null,
                null,
                null,
                null,
                null,
                BigDecimal.valueOf(0.20),
                1024,
                30
            );
        }
        boolean configured() {
            return baseUrl != null && !baseUrl.isBlank();
        }
        String uri(String path) {
            String cleanBase = baseUrl == null ? "" : (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
            String cleanPath = path == null || path.isBlank() ? "/events" : path;
            if (!cleanPath.startsWith("/")) {
                cleanPath = "/" + cleanPath;
            }
            return cleanBase + cleanPath;
        }
        Map<String, String> headers() {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            if ("BEARER".equals(authType) && bearerToken != null && !bearerToken.isBlank()) {
                headers.put("Authorization", "Bearer " + bearerToken);
            } else if ("API_KEY".equals(authType) && apiKey != null && !apiKey.isBlank()) {
                headers.put(apiKeyHeader == null || apiKeyHeader.isBlank() ? "X-API-Key" : apiKeyHeader, apiKey);
            } else if ("BASIC".equals(authType) && username != null && !username.isBlank() && password != null && !password.isBlank()) {
                String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                headers.put("Authorization", "Basic " + token);
            }
            if (mesHeaderAuthSystem() && !headers.containsKey("X-Role")) {
                headers.put("X-Role", "integration_service");
            }
            if (ExternalIntegrationSystem.LIMS.name().equals(systemCode)) {
                headers.putIfAbsent("X-Source-System", "lims-web");
            }
            if ("CLAUDE".equals(systemCode) && providerVersion != null && !providerVersion.isBlank()) {
                headers.put("anthropic-version", providerVersion);
            }
            if (organizationId != null && !organizationId.isBlank()) {
                headers.put("OpenAI-Organization", organizationId);
            }
            if (projectId != null && !projectId.isBlank()) {
                headers.put("OpenAI-Project", projectId);
            }
            return headers;
        }
        private boolean mesHeaderAuthSystem() {
            return ExternalIntegrationSystem.PRODUCTION.name().equals(systemCode)
                || ExternalIntegrationSystem.WMS.name().equals(systemCode)
                || ExternalIntegrationSystem.SCADA.name().equals(systemCode);
        }
    }
    private record ProviderDocument(String fileName, String contentType, byte[] content) {
    }
}
