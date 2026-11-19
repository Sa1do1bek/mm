package com.enterprise.mm.service;

import static com.enterprise.mm.payload.InventoryPeriodCloseWorkspaceDtos.*;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AuditLog;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.StockValuationCostJob;
import com.enterprise.mm.mapper.InventoryAccountingReconciliationMapper;
import com.enterprise.mm.payload.InventoryAccountingReconciliationResponse;
import com.enterprise.mm.payload.InventoryPeriodCloseResponse;
import com.enterprise.mm.payload.InventoryPeriodCloseSearchRequest;
import com.enterprise.mm.payload.InventoryValuationReconciliationIssueResponse;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.InventoryAccountingReconciliationRepository;
import com.enterprise.mm.repository.StockValuationCostJobRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryPeriodCloseWorkspaceService {
    private static final List<String> FAILED_EVENT_STATUSES = List.of("FAILED", "RETRY", "DEAD_LETTER");
    private static final List<String> ACTIVE_JOB_STATUSES = List.of("QUEUED", "RUNNING");
    private static final List<String> FAILED_JOB_STATUSES = List.of("FAILED", "COMPLETED_WITH_ERRORS");

    private final InventoryPeriodCloseService periodCloseService;
    private final InventoryFinanceCalculationService calculationService;
    private final InventoryValuationReconciliationService valuationReconciliationService;
    private final InventoryAccountingReconciliationRepository reconciliationRepository;
    private final InventoryAccountingReconciliationMapper reconciliationMapper;
    private final StockValuationCostJobRepository jobRepository;
    private final IntegrationEventRepository integrationEventRepository;
    private final AuditLogRepository auditLogRepository;
    private final WarehouseRepository warehouseRepository;
    private final FinancialAuthorizationService financialAuthorizationService;

    @Transactional(readOnly = true)
    public Summary summary() {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        List<InventoryPeriodCloseResponse> periods = periodCloseService.search(
            InventoryPeriodCloseSearchRequest.empty(),
            PageRequest.of(0, 500, Sort.by(Sort.Direction.DESC, "createdAt"))
        ).getContent();
        List<StockValuationCostJob> jobs = jobRepository.findHistory(
            tenantId, companyId, PageRequest.of(0, 200)
        ).getContent();
        List<IntegrationEvent> events = financeEvents(tenantId, companyId);

        long active = periods.stream().filter(period -> !"CLOSED".equals(period.getStatus())).count();
        long ready = periods.stream().filter(period -> storedReady(period, tenantId, companyId)).count();
        long openMovements = periods.stream().filter(period -> !"CLOSED".equals(period.getStatus()))
            .mapToLong(InventoryPeriodCloseResponse::getOpenMovementCount).sum();
        BigDecimal fiDifference = periods.stream().filter(period -> !"CLOSED".equals(period.getStatus()))
            .map(InventoryPeriodCloseResponse::getAccountingDifferenceAmount)
            .filter(Objects::nonNull)
            .map(BigDecimal::abs)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new Summary(
            active,
            ready,
            Math.max(0, active - ready),
            openMovements,
            fiDifference,
            jobs.stream().filter(job -> ACTIVE_JOB_STATUSES.contains(enumName(job.getStatus()))).count(),
            jobs.stream().filter(job -> FAILED_JOB_STATUSES.contains(enumName(job.getStatus()))).count(),
            events.stream().filter(event -> FAILED_EVENT_STATUSES.contains(event.getStatus())).count()
        );
    }

    @Transactional(readOnly = true)
    public Workspace workspace(UUID id) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        InventoryPeriodCloseResponse period = periodCloseService.get(id);
        List<String> loadErrors = new ArrayList<>();
        WarehouseContext warehouse = warehouse(period.getWarehouseId(), tenantId, companyId);
        InventoryAccountingReconciliationResponse reconciliation = reconciliation(period, tenantId, companyId, loadErrors);
        List<InventoryValuationReconciliationIssueResponse> valuationIssues = valuationIssues(period, loadErrors);
        List<ValuationJobItem> valuationJobs = valuationJobs(period, tenantId, companyId, loadErrors);
        List<IntegrationImpactItem> integrations = integrations(tenantId, companyId, loadErrors);
        List<AuditTimelineItem> audit = audit(period, reconciliation, tenantId, companyId, loadErrors);
        List<ReadinessCheck> checks = checks(period, reconciliation, valuationIssues, integrations, loadErrors);
        long blockerCount = checks.stream().filter(check -> "BLOCKER".equals(check.severity()) && !check.passed()).count();
        long warningCount = checks.stream().filter(check -> "WARNING".equals(check.severity()) && !check.passed()).count();
        boolean ready = blockerCount == 0 && !"CLOSED".equals(period.getStatus());
        CloseAuthorization authorization = authorization();

        return new Workspace(
            period,
            warehouse,
            checks,
            blockerCount,
            warningCount,
            ready,
            nextAction(period, checks),
            reconciliation,
            valuationIssues,
            valuationJobs,
            integrations,
            audit,
            authorization,
            actions(period, reconciliation, ready),
            List.copyOf(loadErrors)
        );
    }

    private List<ReadinessCheck> checks(
        InventoryPeriodCloseResponse period,
        InventoryAccountingReconciliationResponse reconciliation,
        List<InventoryValuationReconciliationIssueResponse> valuationIssues,
        List<IntegrationImpactItem> integrations,
        List<String> loadErrors
    ) {
        List<ReadinessCheck> checks = new ArrayList<>();
        if ("CLOSED".equals(period.getStatus())) {
            checks.add(check("PERIOD_CLOSED", "INFO", true, 0, "Period is closed", "FINANCE", "Use controlled reopen when a correction is required"));
            return checks;
        }

        long openMovements = safeCount("open movements", loadErrors, () -> calculationService.openMovementCount(period.getWarehouseId(), period.getPeriodStart(), period.getPeriodEnd()));
        long missingCostLayers = safeCount("missing cost layers", loadErrors, () -> calculationService.missingCostLayerBalanceCount(period.getWarehouseId()));
        long zeroCostInbound = safeCount("zero-cost inbound movements", loadErrors, () -> calculationService.zeroCostInboundCount(period.getWarehouseId(), period.getPeriodStart(), period.getPeriodEnd()));
        long openValuations = safeCount("unposted valuation documents", loadErrors, () -> calculationService.openValuationDocumentCount(period.getWarehouseId(), period.getPeriodStart(), period.getPeriodEnd()));
        long openLandedCost = safeCount("open landed-cost lines", loadErrors, () -> calculationService.openLandedCostAllocationLineCount(period.getWarehouseId(), period.getPeriodStart(), period.getPeriodEnd()));
        long valuationBlockers = valuationIssues.stream().filter(issue -> "BLOCKER".equals(issue.severity())).count();

        checks.add(countCheck("OPEN_MOVEMENTS", openMovements, "Open stock movements", "WAREHOUSE", "Post or cancel every open movement in the period"));
        checks.add(countCheck("MISSING_COST_LAYERS", missingCostLayers, "Positive balances without active cost layers", "INVENTORY_ACCOUNTING", "Create or reconcile the missing cost layers"));
        checks.add(countCheck("ZERO_COST_INBOUND", zeroCostInbound, "Posted inbound movements with zero cost", "INVENTORY_ACCOUNTING", "Correct and repost zero-cost inbound movements"));
        checks.add(countCheck("UNPOSTED_VALUATIONS", openValuations, "Unposted valuation documents", "VALUATION", "Post or cancel every valuation document in the period"));
        checks.add(countCheck("OPEN_LANDED_COST", openLandedCost, "Open landed-cost allocation lines", "LANDED_COST", "Post or cancel incomplete landed-cost allocations"));
        checks.add(countCheck("VALUATION_RECONCILIATION", valuationBlockers, "Stock and cost-ledger reconciliation issues", "INVENTORY_ACCOUNTING", "Resolve the listed balance, layer, batch and ledger differences"));

        boolean reconciliationExists = reconciliation != null;
        checks.add(check("FI_RECONCILIATION_EXISTS", "BLOCKER", reconciliationExists, reconciliationExists ? 0 : 1,
            reconciliationExists ? "FI reconciliation is linked" : "FI reconciliation is missing", "FINANCE", "Prepare the period FI reconciliation"));
        boolean posted = reconciliationExists && "POSTED".equals(reconciliation.getStatus());
        checks.add(check("FI_RECONCILIATION_POSTED", "BLOCKER", posted, posted ? 0 : 1,
            posted ? "FI reconciliation is posted" : "FI reconciliation is not posted", "FINANCE", "Reconcile and post the FI reconciliation"));
        boolean matched = reconciliationExists && "MATCHED".equals(reconciliation.getDifferenceStatus());
        checks.add(check("FI_RECONCILIATION_MATCHED", "BLOCKER", matched, matched ? 0 : 1,
            matched ? "MM and FI values are within tolerance" : "MM and FI values are not matched", "FINANCE", "Resolve the difference or update the approved tolerance"));

        long failedIntegrations = integrations.stream().filter(item -> FAILED_EVENT_STATUSES.contains(item.status())).count();
        checks.add(check("FINANCE_INTEGRATIONS", "WARNING", failedIntegrations == 0, failedIntegrations,
            failedIntegrations == 0 ? "No failed finance integration events" : "Finance integration events require attention", "INTEGRATION_OWNER", "Retry or resolve failed finance events before downstream reporting"));
        if (!loadErrors.isEmpty()) {
            checks.add(check("WORKSPACE_DATA_LOAD", "BLOCKER", false, loadErrors.size(), "Readiness data is incomplete", "SYSTEM_SUPPORT", "Restore failed data sources and reload the workspace"));
        }
        return List.copyOf(checks);
    }

    private InventoryAccountingReconciliationResponse reconciliation(
        InventoryPeriodCloseResponse period, String tenantId, UUID companyId, List<String> loadErrors
    ) {
        if (period.getAccountingReconciliationId() == null) {
            return null;
        }
        try {
            return reconciliationRepository.findByIdAndIsDeletedFalse(period.getAccountingReconciliationId())
                .filter(item -> tenantId.equals(item.getTenantId()) && companyId.equals(item.getCompanyId()))
                .map(reconciliationMapper::toResponse)
                .orElse(null);
        } catch (RuntimeException ex) {
            loadErrors.add("FI reconciliation: " + safeMessage(ex));
            return null;
        }
    }

    private List<InventoryValuationReconciliationIssueResponse> valuationIssues(
        InventoryPeriodCloseResponse period, List<String> loadErrors
    ) {
        try {
            return valuationReconciliationService.run(period.getWarehouseId());
        } catch (RuntimeException ex) {
            loadErrors.add("valuation reconciliation: " + safeMessage(ex));
            return List.of();
        }
    }

    private List<ValuationJobItem> valuationJobs(
        InventoryPeriodCloseResponse period, String tenantId, UUID companyId, List<String> loadErrors
    ) {
        try {
            return jobRepository.findHistory(tenantId, companyId, PageRequest.of(0, 25)).stream()
                .filter(job -> period.getWarehouseId() == null || job.getWarehouseId() == null || period.getWarehouseId().equals(job.getWarehouseId()))
                .limit(10)
                .map(this::job)
                .toList();
        } catch (RuntimeException ex) {
            loadErrors.add("valuation jobs: " + safeMessage(ex));
            return List.of();
        }
    }

    private List<IntegrationImpactItem> integrations(String tenantId, UUID companyId, List<String> loadErrors) {
        try {
            return financeEvents(tenantId, companyId).stream().map(this::integration).toList();
        } catch (RuntimeException ex) {
            loadErrors.add("finance integrations: " + safeMessage(ex));
            return List.of();
        }
    }

    private List<IntegrationEvent> financeEvents(String tenantId, UUID companyId) {
        return integrationEventRepository
            .findTop50ByTenantIdAndCompanyIdAndEventTypeStartingWithAndIsDeletedFalseOrderByCreatedAtDesc(tenantId, companyId, "INVENTORY.");
    }

    private List<AuditTimelineItem> audit(
        InventoryPeriodCloseResponse period,
        InventoryAccountingReconciliationResponse reconciliation,
        String tenantId,
        UUID companyId,
        List<String> loadErrors
    ) {
        try {
            List<AuditLog> logs = new ArrayList<>(auditLogRepository
                .findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
                    tenantId, companyId, "InventoryPeriodClose", period.getId()
                ));
            if (reconciliation != null) {
                logs.addAll(auditLogRepository
                    .findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
                        tenantId, companyId, "InventoryAccountingReconciliation", reconciliation.getId()
                    ));
            }
            return logs.stream().sorted(Comparator.comparing(AuditLog::getOccurredAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::auditItem).toList();
        } catch (RuntimeException ex) {
            loadErrors.add("audit package: " + safeMessage(ex));
            return List.of();
        }
    }

    private WarehouseContext warehouse(UUID warehouseId, String tenantId, UUID companyId) {
        if (warehouseId == null) {
            return new WarehouseContext(null, "ALL", "All warehouses");
        }
        return warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(tenantId, companyId, warehouseId)
            .map(item -> new WarehouseContext(item.getId(), item.getCode(), item.getName()))
            .orElse(new WarehouseContext(warehouseId, null, null));
    }

    private CloseAuthorization authorization() {
        boolean allowed = financialAuthorizationService.hasAnyRole("ADMIN", "FINANCE_CONTROLLER", "ACCOUNTANT");
        return new CloseAuthorization(
            "ROLE_AUTHORIZATION",
            allowed ? "AUTHORIZED" : "READ_ONLY",
            List.of("ADMIN", "FINANCE_CONTROLLER", "ACCOUNTANT"),
            "Period close uses finance-role authorization; no separate approval workflow is configured"
        );
    }

    private List<WorkspaceAction> actions(
        InventoryPeriodCloseResponse period,
        InventoryAccountingReconciliationResponse reconciliation,
        boolean ready
    ) {
        boolean finance = financialAuthorizationService.hasAnyRole("ADMIN", "FINANCE_CONTROLLER", "ACCOUNTANT");
        boolean reopen = financialAuthorizationService.hasAnyRole("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER");
        if ("CLOSED".equals(period.getStatus())) {
            return List.of(new WorkspaceAction("REOPEN", true, reopen, true, reopen ? null : "MM manager or finance lead role is required"));
        }
        List<WorkspaceAction> actions = new ArrayList<>();
        String primaryCode = ready ? "CLOSE" : reconciliation == null ? "PREPARE_RECONCILIATION" : "CALCULATE";
        actions.add(new WorkspaceAction("CALCULATE", "CALCULATE".equals(primaryCode), finance, false, finance ? null : "Finance role is required"));
        actions.add(new WorkspaceAction("PREPARE_RECONCILIATION", "PREPARE_RECONCILIATION".equals(primaryCode), finance, false, finance ? null : "Finance role is required"));
        if (reconciliation != null && !"POSTED".equals(reconciliation.getStatus())) {
            actions.add(new WorkspaceAction("RECONCILE", false, finance, false, finance ? null : "Finance role is required"));
        }
        if (reconciliation != null && "RECONCILED".equals(reconciliation.getStatus())) {
            actions.add(new WorkspaceAction("POST_RECONCILIATION", false, finance, false, finance ? null : "Finance role is required"));
        }
        actions.add(new WorkspaceAction("CLOSE", "CLOSE".equals(primaryCode), finance && ready, false,
            !finance ? "Finance role is required" : ready ? null : "Resolve every blocker before close"));
        return List.copyOf(actions);
    }

    private String nextAction(InventoryPeriodCloseResponse period, List<ReadinessCheck> checks) {
        if ("CLOSED".equals(period.getStatus())) return "VERIFY_DOWNSTREAM_OR_REOPEN";
        return checks.stream().filter(check -> "BLOCKER".equals(check.severity()) && !check.passed())
            .map(ReadinessCheck::code).findFirst().orElse("CLOSE_PERIOD");
    }

    private boolean storedReady(InventoryPeriodCloseResponse period, String tenantId, UUID companyId) {
        if ("CLOSED".equals(period.getStatus()) || period.getOpenMovementCount() > 0 || period.getAccountingReconciliationId() == null) {
            return false;
        }
        if (!"MATCHED".equals(period.getAccountingReconciliationStatus())) {
            return false;
        }
        try {
            return reconciliationRepository.findByIdAndIsDeletedFalse(period.getAccountingReconciliationId())
                .filter(r -> tenantId.equals(r.getTenantId()) && (companyId == null || companyId.equals(r.getCompanyId())))
                .map(r -> "POSTED".equals(r.getStatus()) && "MATCHED".equals(r.getDifferenceStatus()))
                .orElse("MATCHED".equals(period.getAccountingReconciliationStatus()));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private long safeCount(String name, List<String> loadErrors, LongSupplier supplier) {
        try {
            return supplier.getAsLong();
        } catch (RuntimeException ex) {
            loadErrors.add(name + ": " + safeMessage(ex));
            return 0;
        }
    }

    private ReadinessCheck countCheck(String code, long count, String message, String owner, String resolution) {
        return check(code, "BLOCKER", count == 0, count, count == 0 ? message + ": clear" : message, owner, resolution);
    }

    private ReadinessCheck check(String code, String severity, boolean passed, long count, String message, String owner, String resolution) {
        return new ReadinessCheck(code, severity, passed, count, message, owner, resolution);
    }

    private ValuationJobItem job(StockValuationCostJob job) {
        return new ValuationJobItem(job.getId(), job.getWarehouseId(), job.getValuationMethod(), job.getRequestedBy(),
            enumName(job.getStatus()), enumName(job.getPhase()), job.getProgressPercent(), job.getTotalItems(),
            job.getProcessedItems(), job.getFailedItems(), job.getErrorMessage(), job.getStartedAt(), job.getFinishedAt(), job.getCreatedAt());
    }

    private IntegrationImpactItem integration(IntegrationEvent event) {
        return new IntegrationImpactItem(event.getId(), event.getEventType(), event.getAggregateType(), event.getAggregateId(),
            event.getStatus(), event.getRetryCount() == null ? 0 : event.getRetryCount(), event.getNextRetryAt(),
            event.getLastError(), event.getCreatedAt());
    }

    private AuditTimelineItem auditItem(AuditLog log) {
        return new AuditTimelineItem(log.getId(), log.getEntityType(), log.getEntityId(), log.getAction(),
            log.getFullName(), log.getDetails(), log.getOccurredAt());
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private String safeMessage(RuntimeException ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
