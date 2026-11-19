package com.enterprise.mm.overview.service;

import com.enterprise.mm.common.OperationalDataQuality;
import com.enterprise.mm.inventory.availability.payload.InventoryAvailabilityDtos.InventoryAvailabilityFilter;
import com.enterprise.mm.inventory.availability.payload.InventoryAvailabilityDtos.InventoryAvailabilityOverview;
import com.enterprise.mm.inventory.availability.service.InventoryAvailabilityWorkspaceService;
import com.enterprise.mm.overview.payload.OperationalOverviewDtos.Health;
import com.enterprise.mm.overview.payload.OperationalOverviewDtos.ProcessStage;
import com.enterprise.mm.overview.payload.OperationalOverviewDtos.RawException;
import com.enterprise.mm.overview.payload.OperationalOverviewDtos.SourceHealth;
import com.enterprise.mm.overview.payload.OperationalOverviewDtos.SourceStatus;
import com.enterprise.mm.overview.service.OperationalOverviewDataSource.CohortSource;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.EventListItem;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.Summary;
import com.enterprise.mm.procurement.command.enums.ProcurementQueueKey;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.ProcurementCommandCaseRow;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandSearchRequest;
import com.enterprise.mm.procurement.command.service.ProcurementCommandQueryService;
import com.enterprise.mm.procurement.command.service.ProcurementQueueService;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.InspectionListItem;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.QueueFilter;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.QueueKey;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.QueueSummary;
import com.enterprise.mm.quality.workspace.service.IncomingQualityWorkspaceService;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.service.IntegrationHealthWorkspaceService;
import com.enterprise.mm.service.ReportService;
import com.enterprise.mm.supplier.governance.service.SupplierGovernanceWorkspaceService;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.GoodsIncomeQueueFilter;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.GoodsIncomeQueueItem;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.GoodsIncomeQueueResponse;
import com.enterprise.mm.warehouse.goodsincome.service.GoodsIncomeWorkspaceService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DefaultOperationalOverviewDataSource implements OperationalOverviewDataSource {
    private static final Logger log = LoggerFactory.getLogger(DefaultOperationalOverviewDataSource.class);
    private static final int EXCEPTION_SAMPLE_SIZE = 12;

    private final PurchaseRequestRepository purchaseRequestRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final ProcurementQueueService procurementQueueService;
    private final ProcurementCommandQueryService procurementQueryService;
    private final InventoryAvailabilityWorkspaceService inventoryService;
    private final GoodsIncomeWorkspaceService goodsIncomeService;
    private final IncomingQualityWorkspaceService qualityService;
    private final SupplierGovernanceWorkspaceService supplierGovernanceService;
    private final IntegrationHealthWorkspaceService integrationService;

    @Override
    public OperationalFacts load() {
        List<SourceHealth> health = new ArrayList<>();
        Instant generatedAt = Instant.now();

        DocumentFacts documents = safe(
            "documents", "Заявки и заказы", "/app/procurement-command", health, generatedAt,
            this::documentFacts);
        ProcurementFacts procurement = safe(
            "procurement", "Закупки", "/app/procurement-command", health, generatedAt,
            this::procurementFacts);
        InventoryAvailabilityOverview inventory = safe(
            "inventory", "Доступность запасов", "/app/stock-balances", health, generatedAt,
            () -> inventoryService.overview(new InventoryAvailabilityFilter(null, null, null, null, null, null, null, null, null)));
        ReceivingFacts receiving = safe(
            "receiving", "Приёмка", "/app/purchase-orders", health, generatedAt,
            this::receivingFacts);
        QualityFacts quality = safe(
            "quality", "Входной контроль", "/app/quality-inspections", health, generatedAt,
            this::qualityFacts);
        Long receivingBlockedSuppliers = safe(
            "suppliers", "Поставщики", "/app/suppliers", health, generatedAt,
            supplierGovernanceService::receivingBlockedCountForCompany);
        IntegrationFacts integrations = safe(
            "integrations", "Интеграции", "/app/integration-events", health, generatedAt,
            this::integrationFacts);

        health.add(new SourceHealth(
            "exceptionTrend",
            "История исключений",
            SourceStatus.DEGRADED,
            "Историческая реконструкция новых и закрытых исключений недоступна; текущие очереди и просрочка показаны полностью.",
            generatedAt,
            null
        ));
        health.add(new SourceHealth(
            "exceptionAging",
            "Возраст исключений",
            SourceStatus.DEGRADED,
            "Возраст рассчитан для ранжированного списка до 25 приоритетных исключений; общие счётчики очередей показаны отдельно.",
            generatedAt,
            null
        ));

        Long criticalOverdue = allPresent(procurement, receiving, quality, integrations)
            ? procurement.overdue + receiving.overdue + quality.overdue + integrations.dead
            : null;
        Long blockedFlow = allPresent(procurement, quality, receivingBlockedSuppliers, integrations)
            ? procurement.blocked + quality.decisionRequired + receivingBlockedSuppliers
                + integrations.failed + integrations.noTransport
            : null;

        List<ProcessStage> stages = List.of(
            stage("REQUEST", "Заявки", documents == null ? null : documents.openRequests,
                "OPEN_PR", "/app/purchase-requests?status=DRAFT&status=APPROVAL_PENDING&status=APPROVED"),
            sourcingStage(procurement == null ? null : procurement.approvedUnsourced),
            stage("ORDER", "Заказы", documents == null ? null : documents.openOrders,
                "OPEN_PO", "/app/purchase-orders?tab=registry&status=DRAFT&status=APPROVAL_PENDING&status=APPROVED&status=SENT&status=PARTIALLY_RECEIVED"),
            receivingStage(receiving == null ? null : receiving.open),
            stage("QUALITY", "Контроль качества", quality == null ? null : quality.open,
                "QUALITY_OPEN", "/app/quality-inspections?queue=OPEN")
        );

        List<RawException> exceptions = new ArrayList<>();
        if (procurement != null) {
            exceptions.addAll(procurement.exceptions);
        }
        if (receiving != null) {
            exceptions.addAll(receiving.exceptions);
        }
        if (quality != null) {
            exceptions.addAll(quality.exceptions);
        }
        if (integrations != null) {
            exceptions.addAll(integrations.exceptions);
        }

        return new OperationalFacts(
            criticalOverdue,
            blockedFlow,
            inventory == null ? null : inventory.materialCount(),
            inventory == null ? null : inventory.shortageCount(),
            List.of(
                cohort("procurement", "Закупки", procurement == null ? null : procurement.overdue,
                    "/app/procurement-command?queue=OVERDUE_WORK"),
                cohort("receiving", "Приёмка", receiving == null ? null : receiving.overdue,
                    "/app/purchase-orders?overdue=true"),
                cohort("quality", "Входной контроль", quality == null ? null : quality.overdue,
                    "/app/quality-inspections?queue=OVERDUE"),
                cohort("integrations", "Интеграции: ручное решение",
                    integrations == null ? null : integrations.dead,
                    "/app/integration-events?queue=DEAD_MANUAL_DECISION")
            ),
            List.of(
                cohort("procurement", "Закупки", procurement == null ? null : procurement.blocked,
                    "/app/procurement-command?queue=PO_DISPATCH_BLOCKED"),
                cohort("quality", "Качество: требуется решение",
                    quality == null ? null : quality.decisionRequired,
                    "/app/quality-inspections?queue=DECISION_REQUIRED"),
                cohort("suppliers", "Поставщики: приёмка заблокирована",
                    receivingBlockedSuppliers,
                    "/app/suppliers?queue=RECEIVING_BLOCKED"),
                cohort("integrationFailed", "Интеграции: ошибки",
                    integrations == null ? null : integrations.failed,
                    "/app/integration-events?queue=FAILED"),
                cohort("integrationTransport", "Интеграции: нет транспорта",
                    integrations == null ? null : integrations.noTransport,
                    "/app/integration-events?queue=NO_TRANSPORT")
            ),
            stages,
            List.of(),
            health,
            exceptions
        );
    }

    private CohortSource cohort(String key, String label, Long count, String url) {
        return new CohortSource(key, label, count, url);
    }

    private DocumentFacts documentFacts() {
        String tenantId = com.enterprise.mm.common.TenantContext.currentTenant();
        java.util.UUID companyId = com.enterprise.mm.common.CompanyContext.currentCompany();
        return new DocumentFacts(
            purchaseRequestRepository.countByTenantIdAndCompanyIdAndStatusInAndIsDeletedFalse(
                tenantId, companyId, ReportService.openPurchaseRequestStatuses()),
            purchaseOrderRepository.countByTenantIdAndCompanyIdAndStatusInAndIsDeletedFalse(
                tenantId, companyId, ReportService.openPurchaseOrderStatuses())
        );
    }

    private ProcurementFacts procurementFacts() {
        long overdue = procurementQueueService.countForCompany(ProcurementQueueKey.OVERDUE_WORK, null);
        long blocked = procurementQueueService.countForCompany(ProcurementQueueKey.PO_DISPATCH_BLOCKED, null);
        long approvedUnsourced = procurementQueueService.countForCompany(
            ProcurementQueueKey.APPROVED_UNSOURCED,
            null
        );
        var request = ProcurementCommandSearchRequest.builder()
            .queue(ProcurementQueueKey.OVERDUE_WORK)
            .build();
        List<RawException> exceptions = procurementQueryService
            .searchForCompany(request, PageRequest.of(0, EXCEPTION_SAMPLE_SIZE))
            .stream()
            // UX-LIVE-09: demo/test/seed purchase requests must not surface as real KPI exceptions.
            .filter(row -> OperationalDataQuality.isOperationalRecord(row.purchaseRequestCode(), row.purchaseRequestName()))
            .map(this::procurementException)
            .toList();
        return new ProcurementFacts(overdue, blocked, approvedUnsourced, exceptions);
    }

    private ReceivingFacts receivingFacts() {
        GoodsIncomeQueueResponse all = goodsIncomeService.queueForCompany(
            new GoodsIncomeQueueFilter(null, List.of(), null, null),
            PageRequest.of(0, 1));
        GoodsIncomeQueueResponse overdue = goodsIncomeService.queueForCompany(
            new GoodsIncomeQueueFilter(null, List.of(), null, true),
            PageRequest.of(0, EXCEPTION_SAMPLE_SIZE));
        return new ReceivingFacts(
            all.totalElements(),
            overdue.totalElements(),
            overdue.items().stream()
                // UX-LIVE-09: demo/test purchase orders must not surface as real KPI exceptions.
                .filter(row -> OperationalDataQuality.isOperationalRecord(
                    row.purchaseOrder() == null ? null : row.purchaseOrder().code(), null))
                .map(this::receivingException)
                .toList()
        );
    }

    private QualityFacts qualityFacts() {
        List<QueueSummary> summaries = qualityService.summary(new QueueFilter(null, null, null, null));
        Map<QueueKey, Long> counts = summaries.stream()
            .collect(Collectors.toMap(QueueSummary::key, QueueSummary::count));
        var overdue = qualityService.list(
            new QueueFilter(QueueKey.OVERDUE, null, null, null),
            PageRequest.of(0, EXCEPTION_SAMPLE_SIZE));
        return new QualityFacts(
            counts.getOrDefault(QueueKey.OPEN, 0L),
            counts.getOrDefault(QueueKey.OVERDUE, 0L),
            counts.getOrDefault(QueueKey.DECISION_REQUIRED, 0L),
            overdue.items().stream()
                // UX-LIVE-09: demo/test inspections must not surface as real KPI exceptions.
                .filter(row -> OperationalDataQuality.isOperationalRecord(row.inspectionNumber(), row.displayName()))
                .map(this::qualityException)
                .toList()
        );
    }

    private IntegrationFacts integrationFacts() {
        Summary summary = integrationService.summaryForCompany();
        List<EventListItem> events = new ArrayList<>();
        events.addAll(integrationService.eventsForCompany(
            IntegrationHealthWorkspaceService.QUEUE_DEAD,
            null,
            PageRequest.of(0, EXCEPTION_SAMPLE_SIZE / 2)).getContent());
        events.addAll(integrationService.eventsForCompany(
            IntegrationHealthWorkspaceService.QUEUE_FAILED,
            null,
            PageRequest.of(0, EXCEPTION_SAMPLE_SIZE / 2)).getContent());
        return new IntegrationFacts(
            summary.failed(),
            summary.noTransport(),
            summary.deadManualDecision(),
            events.stream()
                // UX-LIVE-09: demo/test integration events must not surface as real KPI exceptions.
                .filter(row -> OperationalDataQuality.isOperationalRecord(row.code(), null))
                .map(this::integrationException)
                .toList()
        );
    }

    private RawException procurementException(ProcurementCommandCaseRow row) {
        String id = row.purchaseRequestId() == null ? row.purchaseRequestCode() : row.purchaseRequestId().toString();
        String severity = row.severity() == null ? "WARNING"
            : "CRITICAL".equals(row.severity().name()) || "HIGH".equals(row.severity().name())
                ? "CRITICAL" : "WARNING";
        return new RawException(
            "procurement:" + id,
            "PROCUREMENT_OVERDUE",
            display(row.purchaseRequestCode(), row.purchaseRequestName(), "Просроченная закупочная задача"),
            severity,
            "Риск срыва требуемой даты поставки",
            display(row.ownerUsername(), null, "PROCUREMENT_SPECIALIST"),
            row.dueDate(),
            display(row.nextActionKey(), null, "Проверить закупочную задачу"),
            "PROCUREMENT",
            "/app/procurement-command?queue=OVERDUE_WORK&caseId=" + id,
            row.stage() == null ? "OPEN" : row.stage().name()
        );
    }

    private RawException receivingException(GoodsIncomeQueueItem row) {
        String id = row.purchaseOrder() == null || row.purchaseOrder().id() == null
            ? "unknown" : row.purchaseOrder().id().toString();
        String code = row.purchaseOrder() == null ? null : row.purchaseOrder().code();
        return new RawException(
            "receiving:" + id,
            "RECEIVING_OVERDUE",
            display(code, null, "Просроченная поставка"),
            "CRITICAL",
            "Материал не поступил к ожидаемой дате",
            display(row.ownerRole(), null, "WAREHOUSE_OPERATOR"),
            row.dueDate(),
            display(row.nextAction(), null, "Принять поставку или зафиксировать отклонение"),
            "RECEIVING",
            "/app/purchase-orders/" + id,
            row.purchaseOrder() == null ? "OPEN" : row.purchaseOrder().status()
        );
    }

    private RawException qualityException(InspectionListItem row) {
        String id = row.id() == null ? row.inspectionNumber() : row.id().toString();
        return new RawException(
            "quality:" + id,
            "QUALITY_OVERDUE",
            display(row.inspectionNumber(), row.displayName(), "Просроченный входной контроль"),
            "CRITICAL",
            "Карантинный запас недоступен до решения качества",
            "QUALITY_MANAGER",
            row.dueDate(),
            display(row.nextAction(), null, "Завершить контроль качества"),
            "QUALITY",
            "/app/quality-inspections?queue=OVERDUE&inspectionId=" + id,
            row.status()
        );
    }

    private RawException integrationException(EventListItem row) {
        String id = row.id() == null ? row.code() : row.id().toString();
        LocalDate dueDate = firstDate(row.nextRetryAt(), row.createdAt());
        return new RawException(
            "integration:" + id,
            row.queueKey(),
            display(row.code(), row.eventMeaning(), "Интеграционное исключение"),
            IntegrationHealthWorkspaceService.QUEUE_DEAD.equals(row.queueKey()) ? "CRITICAL" : "WARNING",
            display(row.businessImpact(), null, "Бизнес-документ не синхронизирован"),
            display(row.owner(), null, "INTEGRATION_OWNER"),
            dueDate,
            IntegrationHealthWorkspaceService.QUEUE_DEAD.equals(row.queueKey())
                ? "Принять ручное решение" : "Проверить ошибку и повторить отправку",
            "INTEGRATION",
            "/app/integration-events?queue=" + row.queueKey() + "&eventId=" + id,
            row.status()
        );
    }

    static ProcessStage sourcingStage(Long approvedUnsourcedCount) {
        return stage(
            "SOURCING",
            "Одобрено без источника",
            approvedUnsourcedCount,
            "APPROVED_UNSOURCED",
            "/app/procurement-command?queue=APPROVED_UNSOURCED"
        );
    }

    static ProcessStage receivingStage(Long receivingCount) {
        return stage(
            "RECEIVING",
            "Приёмка",
            receivingCount,
            "RECEIVING",
            "/app/purchase-orders?status=SENT&status=PARTIALLY_RECEIVED"
        );
    }

    private static ProcessStage stage(String key, String label, Long count, String queueKey, String url) {
        return new ProcessStage(
            key,
            label,
            count == null ? 0 : count,
            count == null ? Health.UNAVAILABLE : Health.READY,
            queueKey,
            url
        );
    }

    private <T> T safe(
        String key,
        String label,
        String drilldownUrl,
        List<SourceHealth> health,
        Instant generatedAt,
        Supplier<T> loader
    ) {
        try {
            T value = loader.get();
            health.add(new SourceHealth(key, label, SourceStatus.HEALTHY, null, generatedAt, drilldownUrl));
            return value;
        } catch (RuntimeException exception) {
            log.warn("Operational overview source {} is unavailable: {}", key, exception.getMessage());
            health.add(new SourceHealth(
                key,
                label,
                SourceStatus.UNAVAILABLE,
                "Источник «" + label + "» временно недоступен.",
                null,
                drilldownUrl
            ));
            return null;
        }
    }

    private boolean allPresent(Object... values) {
        for (Object value : values) {
            if (value == null) {
                return false;
            }
        }
        return true;
    }

    private LocalDate firstDate(LocalDateTime primary, LocalDateTime fallback) {
        LocalDateTime value = primary == null ? fallback : primary;
        return value == null ? null : value.toLocalDate();
    }

    private String display(String primary, String secondary, String fallback) {
        if (primary != null && !primary.isBlank() && secondary != null && !secondary.isBlank()) {
            return primary + " · " + secondary;
        }
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (secondary != null && !secondary.isBlank()) {
            return secondary;
        }
        return fallback;
    }

    private record ProcurementFacts(
        long overdue,
        long blocked,
        long approvedUnsourced,
        List<RawException> exceptions
    ) {
    }

    private record DocumentFacts(long openRequests, long openOrders) {
    }

    private record ReceivingFacts(long open, long overdue, List<RawException> exceptions) {
    }

    private record QualityFacts(long open, long overdue, long decisionRequired, List<RawException> exceptions) {
    }

    private record IntegrationFacts(long failed, long noTransport, long dead, List<RawException> exceptions) {
    }
}
