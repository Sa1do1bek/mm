package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.IntegrationRecoveryStatus;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AuditLog;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.AttemptTimelineItem;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.AuditTimelineItem;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.BusinessDocumentReference;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.EventDetail;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.EventListItem;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.QueueDescriptor;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.RelatedBusinessDocument;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.Summary;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.SystemContext;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.WorkspaceAction;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.security.AccessScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IntegrationHealthWorkspaceService {
    public static final String QUEUE_FAILED = "FAILED";
    public static final String QUEUE_RETRY = "RETRY_SCHEDULED";
    public static final String QUEUE_NO_TRANSPORT = "NO_TRANSPORT";
    public static final String QUEUE_RECONCILIATION = "AWAITING_RECONCILIATION";
    public static final String QUEUE_DEAD = "DEAD_MANUAL_DECISION";
    public static final String QUEUE_ALL = "ALL_ACTIVITY";

    private static final List<String> QUEUES = List.of(
        QUEUE_FAILED,
        QUEUE_RETRY,
        QUEUE_NO_TRANSPORT,
        QUEUE_RECONCILIATION,
        QUEUE_DEAD,
        QUEUE_ALL
    );
    private static final Set<String> OPERATE_ROLES = Set.of("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER");

    private final IntegrationEventRepository eventRepository;
    private final AuditLogRepository auditLogRepository;
    private final IntegrationBusinessDocumentResolver documentResolver;
    private final IntegrationPayloadRedactor redactor;
    private final IntegrationSettingService settingService;
    private final ObjectMapper objectMapper;

    public IntegrationHealthWorkspaceService(
        IntegrationEventRepository eventRepository,
        AuditLogRepository auditLogRepository,
        IntegrationBusinessDocumentResolver documentResolver,
        IntegrationPayloadRedactor redactor,
        IntegrationSettingService settingService,
        ObjectMapper objectMapper
    ) {
        this.eventRepository = eventRepository;
        this.auditLogRepository = auditLogRepository;
        this.documentResolver = documentResolver;
        this.redactor = redactor;
        this.settingService = settingService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        return summary(companyFilter());
    }

    @Transactional(readOnly = true)
    public Summary summaryForCompany() {
        return summary(CompanyContext.currentCompany());
    }

    private Summary summary(UUID companyId) {
        LocalDateTime now = LocalDateTime.now();
        return new Summary(
            count(QUEUE_FAILED, now, companyId),
            count(QUEUE_RETRY, now, companyId),
            count(QUEUE_NO_TRANSPORT, now, companyId),
            count(QUEUE_RECONCILIATION, now, companyId),
            count(QUEUE_DEAD, now, companyId),
            eventRepository.countWorkspacePublishedSince(
                TenantContext.currentTenant(),
                companyId,
                LocalDate.now().atStartOfDay()
            )
        );
    }

    @Transactional(readOnly = true)
    public List<QueueDescriptor> queues() {
        LocalDateTime now = LocalDateTime.now();
        return QUEUES.stream()
            .map(queue -> new QueueDescriptor(
                queue,
                "integration.queue." + queue.toLowerCase(Locale.ROOT),
                count(queue, now, companyFilter()),
                Map.of("queue", queue)
            ))
            .toList();
    }

    @Transactional(readOnly = true)
    public Page<EventListItem> events(String queue, String search, Pageable pageable) {
        return events(queue, search, pageable, companyFilter());
    }

    @Transactional(readOnly = true)
    public Page<EventListItem> eventsForCompany(String queue, String search, Pageable pageable) {
        return events(queue, search, pageable, CompanyContext.currentCompany());
    }

    private Page<EventListItem> events(
        String queue,
        String search,
        Pageable pageable,
        UUID companyId
    ) {
        String normalizedQueue = normalizeQueue(queue);
        return eventRepository.searchWorkspace(
            TenantContext.currentTenant(),
            companyId,
            normalizedQueue,
            normalizeSearch(search),
            LocalDateTime.now(),
            IntegrationDispatchService.MAX_AUTOMATIC_RETRIES,
            pageable
        ).map(event -> listItem(event, LocalDateTime.now()));
    }

    @Transactional(readOnly = true)
    public EventDetail detail(UUID eventId) {
        IntegrationEvent event = eventRepository.findWorkspaceEvent(
            TenantContext.currentTenant(),
            companyFilter(),
            eventId
        ).orElseThrow(() -> new NotFoundException("Integration event not found: " + eventId));
        List<AuditLog> audit = auditLogRepository
            .findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
                event.getTenantId(), event.getCompanyId(), "IntegrationEvent", event.getId()
            );
        List<IntegrationEvent> replays = eventRepository
            .findByTenantIdAndCompanyIdAndReplayOfEventIdAndIsDeletedFalseOrderByCreatedAtAsc(
                event.getTenantId(), event.getCompanyId(), event.getId()
            );
        return new EventDetail(
            listItem(event, LocalDateTime.now()),
            systemContext(event),
            requestMetadata(event),
            redactor.responseMetadata(
                event.getStatus(),
                event.getRetryCount(),
                event.getExternalId(),
                event.getLastError(),
                event.getNextRetryAt(),
                event.getLastAttemptAt()
            ),
            audit.stream()
                .filter(item -> isAttemptAction(item.getAction()))
                .map(item -> new AttemptTimelineItem(
                    item.getId(),
                    item.getAction(),
                    attemptOutcome(item.getAction()),
                    item.getFullName(),
                    redactor.sanitizeText(item.getDetails()),
                    item.getOccurredAt()
                ))
                .toList(),
            audit.stream()
                .sorted(Comparator.comparing(AuditLog::getOccurredAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(item -> new AuditTimelineItem(
                    item.getId(),
                    item.getAction(),
                    item.getFullName(),
                    redactor.sanitizeText(item.getDetails()),
                    item.getOccurredAt()
                ))
                .toList(),
            event.getReplayOfEventId(),
            replays.stream().map(IntegrationEvent::getId).toList(),
            redactor.sanitizeText(event.getManualReason()),
            relatedDocuments(event)
        );
    }

    private List<RelatedBusinessDocument> relatedDocuments(IntegrationEvent event) {
        if (event.getAggregateId() == null) {
            return List.of();
        }
        return eventRepository
            .findByTenantIdAndCompanyIdAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
                event.getTenantId(), event.getCompanyId(), event.getAggregateId()
            ).stream()
            .filter(sibling -> !Objects.equals(sibling.getId(), event.getId()))
            .map(sibling -> new RelatedBusinessDocument(
                sibling.getId(),
                sibling.getEventType(),
                sibling.getStatus(),
                documentResolver.resolve(sibling),
                IntegrationRecoveryStatus.fromEventStatus(sibling.getStatus())
            ))
            .toList();
    }

    private long count(String queue, LocalDateTime now, UUID companyId) {
        return eventRepository.searchWorkspace(
            TenantContext.currentTenant(),
            companyId,
            queue,
            null,
            now,
            IntegrationDispatchService.MAX_AUTOMATIC_RETRIES,
            PageRequest.of(0, 1)
        ).getTotalElements();
    }

    private EventListItem listItem(IntegrationEvent event, LocalDateTime now) {
        String queue = primaryQueue(event, now);
        String target = targetSystem(event).map(Enum::name).orElse("EXTERNAL_SYSTEM");
        BusinessDocumentReference document = documentResolver.resolve(event);
        return new EventListItem(
            event.getId(),
            event.getCode(),
            event.getEventType(),
            eventMeaning(event.getEventType()),
            event.getStatus(),
            queue,
            businessImpact(target, queue),
            document,
            owner(target),
            "RB-INTEGRATION-" + target,
            sourceSystem(event),
            target,
            event.getRetryCount(),
            event.getNextRetryAt(),
            event.getLastAttemptAt(),
            event.getCreatedAt(),
            redactor.sanitizeText(event.getLastError()),
            redactor.sanitizeText(event.getExternalId()),
            event.getVersion(),
            actions(event)
        );
    }

    private List<WorkspaceAction> actions(IntegrationEvent event) {
        boolean operator = canOperate();
        String status = normalized(event.getStatus());
        boolean retryState = Set.of("NEW", "FAILED", "RETRY", "DEAD").contains(status);
        boolean replayState = Set.of("PUBLISHED", "FAILED", "RETRY", "DEAD", "CANCELLED").contains(status);
        boolean cancelState = Set.of("NEW", "FAILED", "RETRY", "DEAD").contains(status);
        return List.of(
            action("RETRY", true, operator && retryState, operator ? stateReason(retryState) : "ROLE_REQUIRED"),
            action("REPLAY", false, operator && replayState, operator ? stateReason(replayState) : "ROLE_REQUIRED"),
            action("CANCEL", false, operator && cancelState, operator ? stateReason(cancelState) : "ROLE_REQUIRED")
        );
    }

    private WorkspaceAction action(String code, boolean primary, boolean enabled, String disabledReason) {
        return new WorkspaceAction(code, primary, enabled, true, true, enabled ? null : disabledReason);
    }

    private String stateReason(boolean allowed) {
        return allowed ? null : "STATUS_NOT_ALLOWED";
    }

    private SystemContext systemContext(IntegrationEvent event) {
        String source = sourceSystem(event);
        Optional<ExternalIntegrationSystem> target = targetSystem(event);
        if (target.isEmpty()) {
            boolean noTransport = isNoTransport(event);
            return new SystemContext(
                source,
                "EXTERNAL_SYSTEM",
                "Default integration transport",
                "DEFAULT_OUTBOX",
                !noTransport,
                !noTransport,
                false,
                noTransport ? "NOT_CONFIGURED" : "UNKNOWN",
                noTransport ? "Configure a system endpoint, webhook or message queue before retrying" : "Confirm target-system ownership and reconciliation policy"
            );
        }
        try {
            IntegrationSettingService.RuntimeEndpoint endpoint = settingService.endpoint(target.get());
            boolean configured = endpoint.configured();
            String readiness = !endpoint.enabled()
                ? "DISABLED"
                : !configured
                    ? "NOT_CONFIGURED"
                    : endpoint.mockMode() ? "MOCK_OUTBOX" : "LIVE_CONFIGURED";
            String remediation = switch (readiness) {
                case "DISABLED" -> "Enable the adapter after confirming provider ownership";
                case "NOT_CONFIGURED" -> "Configure and test the provider endpoint before retrying";
                case "MOCK_OUTBOX" -> "Complete provider certification before enabling live delivery";
                default -> "Use the runbook to verify provider response and reconciliation";
            };
            return new SystemContext(
                source,
                target.get().name(),
                target.get().displayName(),
                endpoint.mockMode() ? "MOCK_OUTBOX" : "HTTPS_API",
                endpoint.enabled(),
                configured,
                endpoint.mockMode(),
                readiness,
                remediation
            );
        } catch (RuntimeException exception) {
            return new SystemContext(
                source,
                target.get().name(),
                target.get().displayName(),
                "HTTPS_API",
                false,
                false,
                false,
                "CONFIGURATION_ERROR",
                "Correct the adapter configuration before retrying"
            );
        }
    }

    private ObjectNode requestMetadata(IntegrationEvent event) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("eventCode", event.getCode());
        node.put("eventType", event.getEventType());
        node.put("sourceSystem", sourceSystem(event));
        node.put("targetSystem", targetSystem(event).map(Enum::name).orElse("EXTERNAL_SYSTEM"));
        if (event.getCreatedAt() != null) node.put("createdAt", event.getCreatedAt().toString());
        node.set("payload", redactor.redactPayload(event.getPayload()));
        return node;
    }

    private Optional<ExternalIntegrationSystem> targetSystem(IntegrationEvent event) {
        Optional<ExternalIntegrationSystem> aggregate = externalSystem(event.getAggregateType());
        if (aggregate.isPresent()) return aggregate;
        String eventType = event.getEventType();
        if (eventType == null) return Optional.empty();
        int separator = eventType.indexOf('.');
        return externalSystem(separator > 0 ? eventType.substring(0, separator) : eventType);
    }

    private Optional<ExternalIntegrationSystem> externalSystem(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            return Optional.of(ExternalIntegrationSystem.from(value));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private String sourceSystem(IntegrationEvent event) {
        return event.getEventType() != null && event.getEventType().startsWith("INBOUND.")
            ? targetSystem(event).map(Enum::name).orElse("EXTERNAL_SYSTEM")
            : "MM";
    }

    private String primaryQueue(IntegrationEvent event, LocalDateTime now) {
        String status = normalized(event.getStatus());
        int retries = event.getRetryCount() == null ? 0 : event.getRetryCount();
        if (Set.of("DEAD", "CANCELLED").contains(status) || retries >= IntegrationDispatchService.MAX_AUTOMATIC_RETRIES) return QUEUE_DEAD;
        if (isNoTransport(event) && Set.of("FAILED", "RETRY", "DEAD").contains(status)) return QUEUE_NO_TRANSPORT;
        if ("PUBLISHED".equals(status) && event.getExternalId() == null) return QUEUE_RECONCILIATION;
        if ("RETRY".equals(status) && event.getNextRetryAt() != null && event.getNextRetryAt().isAfter(now)) return QUEUE_RETRY;
        if ("FAILED".equals(status) || ("RETRY".equals(status) && (event.getNextRetryAt() == null || !event.getNextRetryAt().isAfter(now)))) return QUEUE_FAILED;
        return QUEUE_ALL;
    }

    private boolean isNoTransport(IntegrationEvent event) {
        String error = normalized(event.getLastError()).toLowerCase(Locale.ROOT);
        return error.contains("no_integration_transport") || error.contains("not_configured") || error.contains("configuration");
    }

    private String businessImpact(String target, String queue) {
        String outcome = switch (target) {
            case "FI", "ONE_C" -> "financial posting or reconciliation";
            case "TREASURY" -> "payment handoff";
            case "BUDGETING" -> "budget commitment or control";
            case "EDO", "E_IMZO", "DIDOX", "FAKTURA_UZ", "SOLIQ" -> "electronic document exchange";
            case "WMS", "PRODUCTION", "SCADA" -> "warehouse or production execution";
            case "LIMS" -> "quality decision";
            default -> "downstream business processing";
        };
        return switch (queue) {
            case QUEUE_RECONCILIATION -> "Provider confirmation is pending for " + outcome;
            case QUEUE_RETRY -> "A retry is scheduled; " + outcome + " may be delayed";
            case QUEUE_NO_TRANSPORT -> "Transport is unavailable; " + outcome + " has not started";
            case QUEUE_DEAD -> "Automatic recovery stopped; an operator must decide how to continue " + outcome;
            case QUEUE_FAILED -> "Delivery failed; " + outcome + " requires attention";
            default -> "Integration activity affects " + outcome;
        };
    }

    private String owner(String target) {
        return switch (target) {
            case "FI", "ONE_C" -> "Finance integration owner";
            case "TREASURY" -> "Treasury integration owner";
            case "BUDGETING" -> "Budget integration owner";
            case "WMS", "PRODUCTION", "SCADA" -> "Operations integration owner";
            case "EDO", "E_IMZO", "DIDOX", "FAKTURA_UZ", "SOLIQ" -> "Document exchange owner";
            case "LIMS" -> "Quality integration owner";
            default -> "Integration support owner";
        };
    }

    private String eventMeaning(String eventType) {
        if (eventType == null || eventType.isBlank()) return "Integration event";
        String value = eventType;
        int separator = value.indexOf('.');
        if (separator >= 0 && separator < value.length() - 1) value = value.substring(separator + 1);
        value = value.replaceFirst("^(MM|INBOUND)_", "").replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private boolean canOperate() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) return false;
        return authentication.getAuthorities().stream()
            .map(item -> item.getAuthority().replaceFirst("^ROLE_", ""))
            .anyMatch(OPERATE_ROLES::contains);
    }

    private boolean isAttemptAction(String action) {
        String value = normalized(action);
        return value.contains("DISPATCH") || value.contains("RETRY") || value.contains("REPLAY") || value.contains("CANCEL");
    }

    private String attemptOutcome(String action) {
        String value = normalized(action);
        if (value.contains("FAILED") || value.contains("DEAD")) return "FAILED";
        if (value.contains("CANCEL")) return "CANCELLED";
        if (value.contains("DISPATCH") || value.contains("REPLAY") || value.contains("RETRY")) return "COMPLETED";
        return "RECORDED";
    }

    private UUID companyFilter() {
        return AccessScope.companyScoped(true) ? CompanyContext.currentCompany() : null;
    }

    private String normalizeQueue(String queue) {
        String value = normalized(queue);
        return QUEUES.contains(value) ? value : QUEUE_FAILED;
    }

    private String normalizeSearch(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
}
