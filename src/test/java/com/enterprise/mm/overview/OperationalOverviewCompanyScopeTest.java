package com.enterprise.mm.overview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.inventory.availability.service.InventoryAvailabilityWorkspaceService;
import com.enterprise.mm.overview.service.DefaultOperationalOverviewDataSource;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.Summary;
import com.enterprise.mm.procurement.command.service.ProcurementCommandQueryService;
import com.enterprise.mm.procurement.command.service.ProcurementQueueService;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.InspectionListResponse;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.QueueKey;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.QueueSummary;
import com.enterprise.mm.quality.workspace.service.IncomingQualityWorkspaceService;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.service.IntegrationHealthWorkspaceService;
import com.enterprise.mm.supplier.governance.service.SupplierGovernanceWorkspaceService;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.GoodsIncomeQueueResponse;
import com.enterprise.mm.warehouse.goodsincome.service.GoodsIncomeWorkspaceService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;

class OperationalOverviewCompanyScopeTest {

    @AfterEach
    void clearContext() {
        CompanyContext.clear();
        TenantContext.clear();
    }

    @Test
    void usesForcedCurrentCompanyPathsForSourcesThatNormallyExpandForGlobalAdmins() {
        CompanyContext.setCompany(UUID.fromString("40000000-0000-0000-0000-000000000004"));
        TenantContext.setTenant("tenant-a");
        PurchaseRequestRepository purchaseRequests = mock(PurchaseRequestRepository.class);
        PurchaseOrderRepository purchaseOrders = mock(PurchaseOrderRepository.class);
        ProcurementQueueService procurementQueues = mock(ProcurementQueueService.class);
        ProcurementCommandQueryService procurementCases = mock(ProcurementCommandQueryService.class);
        InventoryAvailabilityWorkspaceService inventory = mock(InventoryAvailabilityWorkspaceService.class);
        GoodsIncomeWorkspaceService receiving = mock(GoodsIncomeWorkspaceService.class);
        IncomingQualityWorkspaceService quality = mock(IncomingQualityWorkspaceService.class);
        SupplierGovernanceWorkspaceService suppliers = mock(SupplierGovernanceWorkspaceService.class);
        IntegrationHealthWorkspaceService integrations = mock(IntegrationHealthWorkspaceService.class);
        when(procurementCases.searchForCompany(any(), any())).thenReturn(Page.empty());
        GoodsIncomeQueueResponse emptyReceiving = new GoodsIncomeQueueResponse(
            "receiving", "receiving", null, 0, List.of(), 0, 1, 0, LocalDateTime.now(), "COMPANY");
        when(receiving.queueForCompany(any(), any())).thenReturn(emptyReceiving);
        when(quality.summary(any())).thenReturn(List.of(
            new QueueSummary(QueueKey.OPEN, 0),
            new QueueSummary(QueueKey.OVERDUE, 0),
            new QueueSummary(QueueKey.DECISION_REQUIRED, 0)
        ));
        when(quality.list(any(), any())).thenReturn(new InspectionListResponse(
            QueueKey.OVERDUE, null, 0, List.of(), 0, 1, 0, LocalDateTime.now(), "COMPANY"));
        when(suppliers.receivingBlockedCountForCompany()).thenReturn(5L);
        when(integrations.summaryForCompany()).thenReturn(new Summary(0, 0, 0, 0, 0, 0));
        when(integrations.eventsForCompany(any(), any(), any())).thenReturn(Page.empty());

        var facts = new DefaultOperationalOverviewDataSource(
            purchaseRequests,
            purchaseOrders,
            procurementQueues,
            procurementCases,
            inventory,
            receiving,
            quality,
            suppliers,
            integrations
        ).load();

        assertThat(facts.blockedFlow()).isEqualTo(5L);
        assertThat(facts.blockedFlowSources())
            .filteredOn(source -> source.key().equals("suppliers"))
            .extracting(source -> source.count())
            .containsExactly(5L);
        verify(procurementQueues, atLeastOnce()).countForCompany(any(), any());
        verify(procurementCases).searchForCompany(any(), any());
        verify(receiving, atLeastOnce()).queueForCompany(any(), any());
        verify(suppliers).receivingBlockedCountForCompany();
        verify(suppliers, never()).overviewForCompany(anyInt());
        verify(integrations).summaryForCompany();
        verify(integrations, atLeastOnce()).eventsForCompany(any(), any(), any());
        verify(procurementQueues, never()).count(any(), any());
        verify(procurementCases, never()).search(any(), any());
        verify(receiving, never()).queue(any(), any());
        verify(suppliers, never()).overview(anyInt());
        verify(integrations, never()).summary();
        verify(integrations, never()).events(any(), any(), any());
    }
}
