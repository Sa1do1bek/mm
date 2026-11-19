package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AuditLog;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.InventoryAccountingReconciliation;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.mapper.InventoryAccountingReconciliationMapper;
import com.enterprise.mm.payload.InventoryAccountingReconciliationResponse;
import com.enterprise.mm.payload.InventoryPeriodCloseResponse;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.InventoryAccountingReconciliationRepository;
import com.enterprise.mm.repository.StockValuationCostJobRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class InventoryPeriodCloseWorkspaceServiceTest {
    @Mock InventoryPeriodCloseService periodCloseService;
    @Mock InventoryFinanceCalculationService calculationService;
    @Mock InventoryValuationReconciliationService valuationReconciliationService;
    @Mock InventoryAccountingReconciliationRepository reconciliationRepository;
    @Mock InventoryAccountingReconciliationMapper reconciliationMapper;
    @Mock StockValuationCostJobRepository jobRepository;
    @Mock IntegrationEventRepository integrationEventRepository;
    @Mock AuditLogRepository auditLogRepository;
    @Mock WarehouseRepository warehouseRepository;
    @Mock FinancialAuthorizationService financialAuthorizationService;

    private UUID companyId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("tenant-valuation-close");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "finance.controller", "n/a", List.of(new SimpleGrantedAuthority("ROLE_FINANCE_CONTROLLER"))
        ));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void workspaceUsesExactReadinessChecksAndRealAuditIntegrationContext() {
        UUID periodId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID reconciliationId = UUID.randomUUID();
        InventoryPeriodCloseResponse period = period(periodId, warehouseId, reconciliationId);
        when(periodCloseService.get(periodId)).thenReturn(period);

        Warehouse warehouse = new Warehouse();
        warehouse.setId(warehouseId);
        warehouse.setCode("WH-01");
        warehouse.setName("Main warehouse");
        when(warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, warehouseId
        )).thenReturn(Optional.of(warehouse));

        InventoryAccountingReconciliation reconciliationEntity = new InventoryAccountingReconciliation();
        reconciliationEntity.setId(reconciliationId);
        reconciliationEntity.setTenantId(TenantContext.currentTenant());
        reconciliationEntity.setCompanyId(companyId);
        InventoryAccountingReconciliationResponse reconciliation = new InventoryAccountingReconciliationResponse();
        reconciliation.setId(reconciliationId);
        reconciliation.setStatus("POSTED");
        reconciliation.setDifferenceStatus("MATCHED");
        reconciliation.setDifferenceAmount(BigDecimal.ZERO);
        reconciliation.setToleranceAmount(BigDecimal.ZERO);
        when(reconciliationRepository.findByIdAndIsDeletedFalse(reconciliationId)).thenReturn(Optional.of(reconciliationEntity));
        when(reconciliationMapper.toResponse(reconciliationEntity)).thenReturn(reconciliation);

        when(calculationService.openMovementCount(warehouseId, period.getPeriodStart(), period.getPeriodEnd())).thenReturn(0L);
        when(calculationService.missingCostLayerBalanceCount(warehouseId)).thenReturn(0L);
        when(calculationService.zeroCostInboundCount(warehouseId, period.getPeriodStart(), period.getPeriodEnd())).thenReturn(0L);
        when(calculationService.openValuationDocumentCount(warehouseId, period.getPeriodStart(), period.getPeriodEnd())).thenReturn(0L);
        when(calculationService.openLandedCostAllocationLineCount(warehouseId, period.getPeriodStart(), period.getPeriodEnd())).thenReturn(0L);
        when(valuationReconciliationService.run(warehouseId)).thenReturn(List.of());
        when(jobRepository.findHistory(eq(TenantContext.currentTenant()), eq(companyId), any())).thenReturn(new PageImpl<>(List.of()));

        IntegrationEvent failed = new IntegrationEvent();
        failed.setId(UUID.randomUUID());
        failed.setEventType("INVENTORY.ACCOUNTING_RECONCILIATION_POSTED");
        failed.setAggregateType("InventoryAccountingReconciliation");
        failed.setAggregateId(reconciliationId);
        failed.setStatus("FAILED");
        failed.setLastError("FI endpoint unavailable");
        failed.setCreatedAt(LocalDateTime.of(2026, 7, 15, 9, 0));
        when(integrationEventRepository.findTop50ByTenantIdAndCompanyIdAndEventTypeStartingWithAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), companyId, "INVENTORY."
        )).thenReturn(List.of(failed));

        AuditLog audit = new AuditLog();
        audit.setId(UUID.randomUUID());
        audit.setEntityType("InventoryPeriodClose");
        audit.setEntityId(periodId);
        audit.setAction("CALCULATE");
        audit.setUsername("finance.controller");
        audit.setOccurredAt(LocalDateTime.of(2026, 7, 15, 8, 0));
        when(auditLogRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
            TenantContext.currentTenant(), companyId, "InventoryPeriodClose", periodId
        )).thenReturn(List.of(audit));
        when(auditLogRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
            TenantContext.currentTenant(), companyId, "InventoryAccountingReconciliation", reconciliationId
        )).thenReturn(List.of());
        when(financialAuthorizationService.hasAnyRole("ADMIN", "FINANCE_CONTROLLER", "ACCOUNTANT")).thenReturn(true);

        var workspace = service().workspace(periodId);

        assertThat(workspace.readyToClose()).isTrue();
        assertThat(workspace.blockerCount()).isZero();
        assertThat(workspace.warningCount()).isEqualTo(1);
        assertThat(workspace.checks()).extracting(check -> check.code()).contains(
            "OPEN_MOVEMENTS", "MISSING_COST_LAYERS", "ZERO_COST_INBOUND", "UNPOSTED_VALUATIONS",
            "OPEN_LANDED_COST", "VALUATION_RECONCILIATION", "FI_RECONCILIATION_MATCHED", "FINANCE_INTEGRATIONS"
        );
        assertThat(workspace.integrationEvents()).singleElement().satisfies(item ->
            assertThat(item.lastError()).isEqualTo("FI endpoint unavailable")
        );
        assertThat(workspace.auditTimeline()).singleElement().satisfies(item ->
            assertThat(item.action()).isEqualTo("CALCULATE")
        );
        assertThat(workspace.allowedActions()).filteredOn(action -> "CLOSE".equals(action.code())).singleElement()
            .satisfies(action -> assertThat(action.enabled()).isTrue());
        assertThat(workspace.closeAuthorization().mode()).isEqualTo("ROLE_AUTHORIZATION");
    }

    @Test
    void incompleteReadinessFailsClosedAndViewerGetsNoEnabledCommands() {
        UUID periodId = UUID.randomUUID();
        InventoryPeriodCloseResponse period = period(periodId, null, null);
        when(periodCloseService.get(periodId)).thenReturn(period);
        when(valuationReconciliationService.run(null)).thenThrow(new IllegalStateException("ledger unavailable"));
        when(jobRepository.findHistory(eq(TenantContext.currentTenant()), eq(companyId), any())).thenReturn(new PageImpl<>(List.of()));
        when(integrationEventRepository.findTop50ByTenantIdAndCompanyIdAndEventTypeStartingWithAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), companyId, "INVENTORY."
        )).thenReturn(List.of());
        when(auditLogRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
            TenantContext.currentTenant(), companyId, "InventoryPeriodClose", periodId
        )).thenReturn(List.of());
        when(financialAuthorizationService.hasAnyRole("ADMIN", "FINANCE_CONTROLLER", "ACCOUNTANT")).thenReturn(false);

        var workspace = service().workspace(periodId);

        assertThat(workspace.readyToClose()).isFalse();
        assertThat(workspace.loadErrors()).containsExactly("valuation reconciliation: ledger unavailable");
        assertThat(workspace.checks()).anySatisfy(check -> {
            assertThat(check.code()).isEqualTo("WORKSPACE_DATA_LOAD");
            assertThat(check.passed()).isFalse();
        });
        assertThat(workspace.allowedActions()).noneMatch(action -> action.enabled());
        assertThat(workspace.closeAuthorization().status()).isEqualTo("READ_ONLY");
    }

    @Test
    void summaryAggregatesActivePeriodsReadinessAndFinanceIntegrations() {
        UUID readyPeriodId = UUID.randomUUID();
        UUID blockedPeriodId = UUID.randomUUID();
        UUID closedPeriodId = UUID.randomUUID();
        UUID recId = UUID.randomUUID();

        InventoryPeriodCloseResponse readyPeriod = period(readyPeriodId, UUID.randomUUID(), recId);
        readyPeriod.setOpenMovementCount(0);
        readyPeriod.setStatus("CALCULATED");
        readyPeriod.setAccountingReconciliationStatus("MATCHED");
        readyPeriod.setAccountingDifferenceAmount(BigDecimal.ZERO);

        InventoryPeriodCloseResponse blockedPeriod = period(blockedPeriodId, UUID.randomUUID(), null);
        blockedPeriod.setOpenMovementCount(3);
        blockedPeriod.setStatus("DRAFT");
        blockedPeriod.setAccountingReconciliationStatus(null);
        blockedPeriod.setAccountingDifferenceAmount(new BigDecimal("150.50"));

        InventoryPeriodCloseResponse closedPeriod = period(closedPeriodId, UUID.randomUUID(), null);
        closedPeriod.setStatus("CLOSED");

        when(periodCloseService.search(any(), any())).thenReturn(new PageImpl<>(List.of(readyPeriod, blockedPeriod, closedPeriod)));

        InventoryAccountingReconciliation recEntity = new InventoryAccountingReconciliation();
        recEntity.setId(recId);
        recEntity.setTenantId(TenantContext.currentTenant());
        recEntity.setCompanyId(companyId);
        recEntity.setStatus("POSTED");
        recEntity.setDifferenceStatus("MATCHED");
        when(reconciliationRepository.findByIdAndIsDeletedFalse(recId)).thenReturn(Optional.of(recEntity));

        com.enterprise.mm.entity.StockValuationCostJob jobQueued = new com.enterprise.mm.entity.StockValuationCostJob();
        jobQueued.setStatus(com.enterprise.mm.enums.StockValuationCostJobStatus.QUEUED);
        com.enterprise.mm.entity.StockValuationCostJob jobFailed = new com.enterprise.mm.entity.StockValuationCostJob();
        jobFailed.setStatus(com.enterprise.mm.enums.StockValuationCostJobStatus.FAILED);
        com.enterprise.mm.entity.StockValuationCostJob jobDone = new com.enterprise.mm.entity.StockValuationCostJob();
        jobDone.setStatus(com.enterprise.mm.enums.StockValuationCostJobStatus.COMPLETED);
        when(jobRepository.findHistory(eq(TenantContext.currentTenant()), eq(companyId), any()))
            .thenReturn(new PageImpl<>(List.of(jobQueued, jobFailed, jobDone)));

        IntegrationEvent eventFailed = new IntegrationEvent();
        eventFailed.setStatus("FAILED");
        IntegrationEvent eventDone = new IntegrationEvent();
        eventDone.setStatus("PROCESSED");
        when(integrationEventRepository.findTop50ByTenantIdAndCompanyIdAndEventTypeStartingWithAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), companyId, "INVENTORY."
        )).thenReturn(List.of(eventFailed, eventDone));

        var summary = service().summary();

        assertThat(summary.activePeriods()).isEqualTo(2);
        assertThat(summary.readyPeriods()).isEqualTo(1);
        assertThat(summary.blockedPeriods()).isEqualTo(1);
        assertThat(summary.openMovements()).isEqualTo(3);
        assertThat(summary.fiDifference()).isEqualByComparingTo("150.50");
        assertThat(summary.activeValuationJobs()).isEqualTo(1);
        assertThat(summary.failedValuationJobs()).isEqualTo(1);
        assertThat(summary.failedFinanceIntegrations()).isEqualTo(1);
    }

    private InventoryPeriodCloseResponse period(UUID id, UUID warehouseId, UUID reconciliationId) {
        InventoryPeriodCloseResponse period = new InventoryPeriodCloseResponse();
        period.setId(id);
        period.setTenantId(TenantContext.currentTenant());
        period.setCompanyId(companyId);
        period.setStatus("CALCULATED");
        period.setVersion(4L);
        period.setPeriodStart(LocalDate.of(2026, 6, 1));
        period.setPeriodEnd(LocalDate.of(2026, 6, 30));
        period.setWarehouseId(warehouseId);
        period.setAccountingReconciliationId(reconciliationId);
        period.setAccountingReconciliationStatus(reconciliationId == null ? null : "MATCHED");
        period.setAccountingDifferenceAmount(BigDecimal.ZERO);
        return period;
    }

    private InventoryPeriodCloseWorkspaceService service() {
        return new InventoryPeriodCloseWorkspaceService(
            periodCloseService,
            calculationService,
            valuationReconciliationService,
            reconciliationRepository,
            reconciliationMapper,
            jobRepository,
            integrationEventRepository,
            auditLogRepository,
            warehouseRepository,
            financialAuthorizationService
        );
    }
}
