package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.DemandPlanItem;
import com.enterprise.mm.entity.PurchaseOrderSourceAllocation;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.payload.PurchaseOrderItemResponse;
import com.enterprise.mm.payload.PurchaseOrderResponse;
import com.enterprise.mm.payload.PurchaseRequestItemResponse;
import com.enterprise.mm.payload.PurchaseRequestResponse;
import com.enterprise.mm.repository.DemandPlanItemRepository;
import com.enterprise.mm.repository.DemandPlanRepository;
import com.enterprise.mm.repository.PurchaseOrderSourceAllocationRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProcurementLineageEnricherTest {
    private static final String TENANT = "default";

    @Mock private DemandPlanRepository demandPlanRepository;
    @Mock private DemandPlanItemRepository demandPlanItemRepository;
    @Mock private PurchaseRequestRepository purchaseRequestRepository;
    @Mock private PurchaseRequestItemRepository purchaseRequestItemRepository;
    @Mock private PurchaseOrderSourceAllocationRepository purchaseOrderSourceAllocationRepository;
    @InjectMocks private ProcurementLineageEnricher enricher;

    private UUID companyId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        companyId = CompanyContext.DEFAULT_COMPANY_ID;
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void enrichPurchaseRequestAddsDemandPlanHeader() {
        UUID demandPlanId = UUID.randomUUID();
        DemandPlan demandPlan = demandPlan(demandPlanId, "DP-001", "ru:Plan RU");

        PurchaseRequestResponse response = new PurchaseRequestResponse();
        response.setDemandPlanId(demandPlanId);

        when(demandPlanRepository.findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(
            eq(TENANT), eq(companyId), any()))
            .thenReturn(List.of(demandPlan));

        enricher.enrichPurchaseRequest(response);

        assertThat(response.getDemandPlanCode()).isEqualTo("DP-001");
        assertThat(response.getDemandPlanName()).isEqualTo("Plan RU");
    }

    @Test
    void enrichPurchaseRequestItemAddsSourceDemandPlanFields() {
        UUID purchaseRequestItemId = UUID.randomUUID();
        UUID demandPlanId = UUID.randomUUID();
        UUID demandPlanItemId = UUID.randomUUID();

        DemandPlan demandPlan = demandPlan(demandPlanId, "DP-001", "ru:Plan RU");
        DemandPlanItem demandPlanItem = demandPlanItem(
            demandPlanItemId,
            demandPlanId,
            purchaseRequestItemId,
            "DPI-001",
            "ru:Item RU"
        );

        PurchaseRequestItemResponse response = new PurchaseRequestItemResponse();
        response.setId(purchaseRequestItemId);

        when(demandPlanItemRepository.findByTenantIdAndCompanyIdAndPurchaseRequestItemIdInAndIsDeletedFalse(
            eq(TENANT), eq(companyId), any()))
            .thenReturn(List.of(demandPlanItem));
        when(demandPlanRepository.findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(
            eq(TENANT), eq(companyId), any()))
            .thenReturn(List.of(demandPlan));

        enricher.enrichPurchaseRequestItem(response);

        assertThat(response.getSourceDemandPlanItemId()).isEqualTo(demandPlanItemId);
        assertThat(response.getSourceDemandPlanItemCode()).isEqualTo("DPI-001");
        assertThat(response.getSourceDemandPlanItemName()).isEqualTo("Item RU");
        assertThat(response.getSourceDemandPlanId()).isEqualTo(demandPlanId);
        assertThat(response.getSourceDemandPlanCode()).isEqualTo("DP-001");
        assertThat(response.getSourceDemandPlanName()).isEqualTo("Plan RU");
    }

    @Test
    void enrichPurchaseOrderAddsDemandPlanFromPurchaseRequest() {
        UUID purchaseRequestId = UUID.randomUUID();
        UUID demandPlanId = UUID.randomUUID();

        PurchaseRequest purchaseRequest = new PurchaseRequest();
        purchaseRequest.setId(purchaseRequestId);
        purchaseRequest.setDemandPlanId(demandPlanId);

        DemandPlan demandPlan = demandPlan(demandPlanId, "DP-001", "ru:Plan RU");

        PurchaseOrderResponse response = new PurchaseOrderResponse();
        response.setPurchaseRequestId(purchaseRequestId);

        when(purchaseRequestRepository.findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(
            eq(TENANT), eq(companyId), any()))
            .thenReturn(List.of(purchaseRequest));
        when(demandPlanRepository.findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(
            eq(TENANT), eq(companyId), any()))
            .thenReturn(List.of(demandPlan));

        enricher.enrichPurchaseOrder(response);

        assertThat(response.getDemandPlanId()).isEqualTo(demandPlanId);
        assertThat(response.getDemandPlanCode()).isEqualTo("DP-001");
        assertThat(response.getDemandPlanName()).isEqualTo("Plan RU");
    }

    @Test
    void enrichPurchaseOrderItemMarksUnavailableWhenNoAllocations() {
        UUID purchaseOrderItemId = UUID.randomUUID();

        PurchaseOrderItemResponse response = new PurchaseOrderItemResponse();
        response.setId(purchaseOrderItemId);

        when(purchaseOrderSourceAllocationRepository.findByTenantIdAndCompanyIdAndPurchaseOrderItemIdInAndIsDeletedFalse(
            eq(TENANT), eq(companyId), any()))
            .thenReturn(List.of());

        enricher.enrichPurchaseOrderItem(response);

        assertThat(response.isSourceAllocationAvailable()).isFalse();
        assertThat(response.getSourceAllocations()).isEmpty();
    }

    @Test
    void enrichPurchaseOrderItemAddsAllocationLineage() {
        UUID purchaseOrderItemId = UUID.randomUUID();
        UUID purchaseRequestItemId = UUID.randomUUID();
        UUID demandPlanId = UUID.randomUUID();
        UUID demandPlanItemId = UUID.randomUUID();
        UUID allocationId = UUID.randomUUID();

        PurchaseOrderSourceAllocation allocation = new PurchaseOrderSourceAllocation();
        allocation.setId(allocationId);
        allocation.setPurchaseOrderItemId(purchaseOrderItemId);
        allocation.setPurchaseRequestItemId(purchaseRequestItemId);
        allocation.setOrderedQuantity(BigDecimal.valueOf(5));

        PurchaseRequestItem purchaseRequestItem = new PurchaseRequestItem();
        purchaseRequestItem.setId(purchaseRequestItemId);
        purchaseRequestItem.setCode("PRI-001");
        purchaseRequestItem.setName("ru:PR Item");

        DemandPlan demandPlan = demandPlan(demandPlanId, "DP-001", "ru:Plan RU");
        DemandPlanItem demandPlanItem = demandPlanItem(
            demandPlanItemId,
            demandPlanId,
            purchaseRequestItemId,
            "DPI-001",
            "ru:Item RU"
        );

        PurchaseOrderItemResponse response = new PurchaseOrderItemResponse();
        response.setId(purchaseOrderItemId);

        when(purchaseOrderSourceAllocationRepository.findByTenantIdAndCompanyIdAndPurchaseOrderItemIdInAndIsDeletedFalse(
            eq(TENANT), eq(companyId), any()))
            .thenReturn(List.of(allocation));
        when(demandPlanItemRepository.findByTenantIdAndCompanyIdAndPurchaseRequestItemIdInAndIsDeletedFalse(
            eq(TENANT), eq(companyId), any()))
            .thenReturn(List.of(demandPlanItem));
        when(purchaseRequestItemRepository.findWorkspaceLineageItems(
            eq(TENANT), eq(companyId), any()))
            .thenReturn(List.of(purchaseRequestItem));
        when(demandPlanRepository.findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(
            eq(TENANT), eq(companyId), any()))
            .thenReturn(List.of(demandPlan));

        enricher.enrichPurchaseOrderItem(response);

        assertThat(response.isSourceAllocationAvailable()).isTrue();
        assertThat(response.getSourceAllocations()).hasSize(1);
        assertThat(response.getSourceAllocations().getFirst().getAllocationId()).isEqualTo(allocationId);
        assertThat(response.getSourceAllocations().getFirst().getPurchaseRequestItemCode()).isEqualTo("PRI-001");
        assertThat(response.getSourceAllocations().getFirst().getDemandPlanItemId()).isEqualTo(demandPlanItemId);
        assertThat(response.getSourceAllocations().getFirst().getDemandPlanCode()).isEqualTo("DP-001");
    }

    private DemandPlan demandPlan(UUID id, String code, String name) {
        DemandPlan plan = new DemandPlan();
        plan.setId(id);
        plan.setTenantId(TENANT);
        plan.setCompanyId(companyId);
        plan.setCode(code);
        plan.setName(name);
        return plan;
    }

    private DemandPlanItem demandPlanItem(
        UUID id,
        UUID demandPlanId,
        UUID purchaseRequestItemId,
        String code,
        String name
    ) {
        DemandPlanItem item = new DemandPlanItem();
        item.setId(id);
        item.setTenantId(TENANT);
        item.setCompanyId(companyId);
        item.setDemandPlanId(demandPlanId);
        item.setPurchaseRequestItemId(purchaseRequestItemId);
        item.setCode(code);
        item.setName(name);
        return item;
    }
}
