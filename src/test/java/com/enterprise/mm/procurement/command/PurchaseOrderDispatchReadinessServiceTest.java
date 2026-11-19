package com.enterprise.mm.procurement.command;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.DemandPlanItem;
import com.enterprise.mm.entity.DemandPlanItemPurchaseRequestAllocation;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseOrderSourceAllocation;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.DispatchReadiness;
import com.enterprise.mm.procurement.command.service.PurchaseOrderDispatchReadinessService;
import com.enterprise.mm.payload.SupplierEligibilityDtos.MaterialGroupEligibility;
import com.enterprise.mm.payload.SupplierEligibilityDtos.AccreditationDemand;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.DemandPlanItemPurchaseRequestAllocationRepository;
import com.enterprise.mm.repository.DemandPlanItemRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseOrderSourceAllocationRepository;
import com.enterprise.mm.service.ContractService;
import com.enterprise.mm.service.SupplierAccreditationService;
import com.enterprise.mm.service.SupplierAccreditationDemandResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PurchaseOrderDispatchReadinessServiceTest {

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void demandPlanOverageBlocksDispatchReadinessAndRequireReady() {
        UUID poId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID poItemId = UUID.randomUUID();
        UUID purchaseRequestItemId = UUID.randomUUID();
        UUID demandPlanItemId = UUID.randomUUID();
        PurchaseOrderRepository poRepository = mock(PurchaseOrderRepository.class);
        PurchaseOrderItemRepository itemRepository = mock(PurchaseOrderItemRepository.class);
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        SupplierAccreditationService accreditationService = mock(SupplierAccreditationService.class);
        ContractService contractService = mock(ContractService.class);
        PurchaseOrderSourceAllocationRepository sourceAllocationRepository = mock(PurchaseOrderSourceAllocationRepository.class);
        DemandPlanItemPurchaseRequestAllocationRepository demandAllocationRepository = mock(DemandPlanItemPurchaseRequestAllocationRepository.class);
        DemandPlanItemRepository demandPlanItemRepository = mock(DemandPlanItemRepository.class);
        PurchaseOrder po = approvedPo(poId, supplierId);
        PurchaseOrderItem item = validItem(poId);
        item.setId(poItemId);
        item.setQuantity(new BigDecimal("12"));
        item.setCompanyId(po.getCompanyId());
        PurchaseOrderSourceAllocation poAllocation = new PurchaseOrderSourceAllocation();
        poAllocation.setPurchaseOrderItemId(poItemId);
        poAllocation.setPurchaseRequestItemId(purchaseRequestItemId);
        poAllocation.setOrderedQuantity(new BigDecimal("12"));
        DemandPlanItemPurchaseRequestAllocation demandAllocation = new DemandPlanItemPurchaseRequestAllocation();
        demandAllocation.setDemandPlanItemId(demandPlanItemId);
        demandAllocation.setPurchaseRequestItemId(purchaseRequestItemId);
        demandAllocation.setAllocatedQuantity(new BigDecimal("10"));
        DemandPlanItem demandPlanItem = new DemandPlanItem();
        demandPlanItem.setId(demandPlanItemId);
        demandPlanItem.setTenantId("tenant-a");
        demandPlanItem.setCompanyId(po.getCompanyId());
        demandPlanItem.setCode("DPI-10");
        demandPlanItem.setApprovedQuantity(new BigDecimal("10"));
        when(poRepository.findByIdAndIsDeletedFalse(poId)).thenReturn(Optional.of(po));
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("tenant-a", poId))
            .thenReturn(List.of(item));
        when(sourceAllocationRepository.findByTenantIdAndCompanyIdAndPurchaseOrderItemIdInAndIsDeletedFalse(
            "tenant-a", po.getCompanyId(), List.of(poItemId)))
            .thenReturn(List.of(poAllocation));
        when(sourceAllocationRepository.findByTenantIdAndCompanyIdAndPurchaseRequestItemIdInAndIsDeletedFalseOrderByCreatedAtAsc(
            "tenant-a", po.getCompanyId(), List.of(purchaseRequestItemId)))
            .thenReturn(List.of(poAllocation));
        when(demandAllocationRepository.findByTenantIdAndCompanyIdAndPurchaseRequestItemIdInAndIsDeletedFalseOrderByCreatedAtAsc(
            "tenant-a", po.getCompanyId(), List.of(purchaseRequestItemId)))
            .thenReturn(List.of(demandAllocation));
        when(demandAllocationRepository.findByTenantIdAndCompanyIdAndDemandPlanItemIdInAndIsDeletedFalseOrderByCreatedAtAsc(
            "tenant-a", po.getCompanyId(), Set.of(demandPlanItemId)))
            .thenReturn(List.of(demandAllocation));
        when(demandPlanItemRepository.findAllById(List.of(demandPlanItemId)))
            .thenReturn(List.of(demandPlanItem));
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(po.getCompanyId());
        PurchaseOrderDispatchReadinessService service = new PurchaseOrderDispatchReadinessService(
            poRepository, itemRepository, new SupplierAccreditationDemandResolver(materialRepository), accreditationService,
            contractService, sourceAllocationRepository, demandAllocationRepository, demandPlanItemRepository);

        DispatchReadiness readiness = service.evaluate(poId);

        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.checks()).anyMatch(check ->
            check.code().equals("PROCUREMENT_DEMAND_PLAN_QUANTITY_EXCEEDED")
                && check.blocking()
                && String.valueOf(check.parameters().get("reason"))
                    .contains("Purchase order quantity exceeds approved demand plan quantity for DPI-10"));
        assertThatThrownBy(() -> service.requireReady(poId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("PROCUREMENT_DEMAND_PLAN_QUANTITY_EXCEEDED");
    }

    @Test
    void demandPlanAmountOverageBlocksDispatchReadinessWhenQuantityIsWithinPlan() {
        UUID poId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID poItemId = UUID.randomUUID();
        UUID purchaseRequestItemId = UUID.randomUUID();
        UUID demandPlanItemId = UUID.randomUUID();
        PurchaseOrderRepository poRepository = mock(PurchaseOrderRepository.class);
        PurchaseOrderItemRepository itemRepository = mock(PurchaseOrderItemRepository.class);
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        SupplierAccreditationService accreditationService = mock(SupplierAccreditationService.class);
        ContractService contractService = mock(ContractService.class);
        PurchaseOrderSourceAllocationRepository sourceAllocationRepository = mock(PurchaseOrderSourceAllocationRepository.class);
        DemandPlanItemPurchaseRequestAllocationRepository demandAllocationRepository = mock(DemandPlanItemPurchaseRequestAllocationRepository.class);
        DemandPlanItemRepository demandPlanItemRepository = mock(DemandPlanItemRepository.class);
        PurchaseOrder po = approvedPo(poId, supplierId);
        PurchaseOrderItem item = validItem(poId);
        item.setId(poItemId);
        item.setQuantity(new BigDecimal("10"));
        item.setUnitPrice(new BigDecimal("15"));
        item.setCompanyId(po.getCompanyId());
        PurchaseOrderSourceAllocation poAllocation = new PurchaseOrderSourceAllocation();
        poAllocation.setPurchaseOrderItemId(poItemId);
        poAllocation.setPurchaseRequestItemId(purchaseRequestItemId);
        poAllocation.setOrderedQuantity(new BigDecimal("10"));
        DemandPlanItemPurchaseRequestAllocation demandAllocation = new DemandPlanItemPurchaseRequestAllocation();
        demandAllocation.setDemandPlanItemId(demandPlanItemId);
        demandAllocation.setPurchaseRequestItemId(purchaseRequestItemId);
        demandAllocation.setAllocatedQuantity(new BigDecimal("10"));
        demandAllocation.setAllocatedAmount(new BigDecimal("100"));
        DemandPlanItem demandPlanItem = new DemandPlanItem();
        demandPlanItem.setId(demandPlanItemId);
        demandPlanItem.setTenantId("tenant-a");
        demandPlanItem.setCompanyId(po.getCompanyId());
        demandPlanItem.setCode("DPI-AMT");
        demandPlanItem.setApprovedQuantity(new BigDecimal("10"));
        demandPlanItem.setEstimatedAmount(new BigDecimal("100"));
        when(poRepository.findByIdAndIsDeletedFalse(poId)).thenReturn(Optional.of(po));
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("tenant-a", poId))
            .thenReturn(List.of(item));
        when(sourceAllocationRepository.findByTenantIdAndCompanyIdAndPurchaseOrderItemIdInAndIsDeletedFalse(
            "tenant-a", po.getCompanyId(), List.of(poItemId)))
            .thenReturn(List.of(poAllocation));
        when(sourceAllocationRepository.findByTenantIdAndCompanyIdAndPurchaseRequestItemIdInAndIsDeletedFalseOrderByCreatedAtAsc(
            "tenant-a", po.getCompanyId(), List.of(purchaseRequestItemId)))
            .thenReturn(List.of(poAllocation));
        when(demandAllocationRepository.findByTenantIdAndCompanyIdAndPurchaseRequestItemIdInAndIsDeletedFalseOrderByCreatedAtAsc(
            "tenant-a", po.getCompanyId(), List.of(purchaseRequestItemId)))
            .thenReturn(List.of(demandAllocation));
        when(demandAllocationRepository.findByTenantIdAndCompanyIdAndDemandPlanItemIdInAndIsDeletedFalseOrderByCreatedAtAsc(
            "tenant-a", po.getCompanyId(), Set.of(demandPlanItemId)))
            .thenReturn(List.of(demandAllocation));
        when(demandPlanItemRepository.findAllById(List.of(demandPlanItemId)))
            .thenReturn(List.of(demandPlanItem));
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(po.getCompanyId());
        PurchaseOrderDispatchReadinessService service = new PurchaseOrderDispatchReadinessService(
            poRepository, itemRepository, new SupplierAccreditationDemandResolver(materialRepository), accreditationService,
            contractService, sourceAllocationRepository, demandAllocationRepository, demandPlanItemRepository);

        DispatchReadiness readiness = service.evaluate(poId);

        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.checks()).anyMatch(check ->
            check.code().equals("PROCUREMENT_DEMAND_PLAN_AMOUNT_EXCEEDED")
                && String.valueOf(check.parameters().get("reason"))
                    .contains("Purchase order amount exceeds approved demand plan amount for DPI-AMT"));
    }

    @Test
    void blockedSupplierIsBlockingInReadinessAndRequireReady() {
        UUID poId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        PurchaseOrderRepository poRepository = mock(PurchaseOrderRepository.class);
        PurchaseOrderItemRepository itemRepository = mock(PurchaseOrderItemRepository.class);
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        SupplierAccreditationService accreditationService = mock(SupplierAccreditationService.class);
        ContractService contractService = mock(ContractService.class);
        PurchaseOrder po = approvedPo(poId, supplierId);
        PurchaseOrderItem item = validItem(poId);
        item.setCompanyId(po.getCompanyId());
        when(poRepository.findByIdAndIsDeletedFalse(poId)).thenReturn(Optional.of(po));
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("tenant-a", poId))
            .thenReturn(List.of(item));
        when(accreditationService.supplierEligibilityBlockedReasonForDemand(
            supplierId, new AccreditationDemand(Set.of(), true, false, false, false), "purchase order dispatch"))
            .thenReturn("Supplier is blocked");
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(po.getCompanyId());
        PurchaseOrderDispatchReadinessService service = service(
            poRepository, itemRepository, materialRepository, accreditationService, contractService);

        DispatchReadiness readiness = service.evaluate(poId);

        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.checks()).anyMatch(check ->
            check.code().equals("PROCUREMENT_SUPPLIER_BLOCKED") && check.blocking());
        assertThatThrownBy(() -> service.requireReady(poId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("PROCUREMENT_SUPPLIER_BLOCKED");
    }

    @Test
    void dispatchReadinessChecksAccreditationForEveryMaterialGroup() {
        UUID poId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID materialGroupId = UUID.randomUUID();
        PurchaseOrderRepository poRepository = mock(PurchaseOrderRepository.class);
        PurchaseOrderItemRepository itemRepository = mock(PurchaseOrderItemRepository.class);
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        SupplierAccreditationService accreditationService = mock(SupplierAccreditationService.class);
        ContractService contractService = mock(ContractService.class);
        PurchaseOrder po = approvedPo(poId, supplierId);
        PurchaseOrderItem item = validItem(poId);
        Material material = new Material();
        material.setId(UUID.randomUUID());
        material.setTenantId("tenant-a");
        material.setCompanyId(po.getCompanyId());
        material.setMaterialGroupId(materialGroupId);
        item.setMaterial(material);
        item.setMaterialId(material.getId());
        item.setCompanyId(po.getCompanyId());
        when(poRepository.findByIdAndIsDeletedFalse(poId)).thenReturn(Optional.of(po));
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("tenant-a", poId))
            .thenReturn(List.of(item));
        when(materialRepository.findByTenantIdAndIdAndIsDeletedFalse("tenant-a", material.getId()))
            .thenReturn(Optional.of(material));
        when(accreditationService.materialGroupEligibility(
            supplierId, List.of(materialGroupId), "purchase order dispatch"))
            .thenReturn(List.of(new MaterialGroupEligibility(
                materialGroupId, true, null, null, UUID.randomUUID(), "APPROVED",
                UUID.randomUUID(), LocalDate.now().plusDays(30), null)));
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(po.getCompanyId());
        PurchaseOrderDispatchReadinessService service = service(
            poRepository, itemRepository, materialRepository, accreditationService, contractService);

        DispatchReadiness readiness = service.evaluate(poId);

        assertThat(readiness.ready()).isTrue();
        assertThat(readiness.materialGroupEligibility()).singleElement().satisfies(row -> {
            assertThat(row.materialGroupId()).isEqualTo(materialGroupId);
            assertThat(row.eligible()).isTrue();
        });
        verify(accreditationService).supplierEligibilityBlockedReasonForDemand(
            supplierId, new AccreditationDemand(Set.of(materialGroupId), true, true, false, false),
            "purchase order dispatch");
    }

    @Test
    void dispatchReadinessResolvesMaterialGroupFromMaterialIdWhenRelationIsNotLoaded() {
        UUID poId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID materialGroupId = UUID.randomUUID();
        PurchaseOrderRepository poRepository = mock(PurchaseOrderRepository.class);
        PurchaseOrderItemRepository itemRepository = mock(PurchaseOrderItemRepository.class);
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        SupplierAccreditationService accreditationService = mock(SupplierAccreditationService.class);
        ContractService contractService = mock(ContractService.class);
        PurchaseOrder po = approvedPo(poId, supplierId);
        PurchaseOrderItem item = validItem(poId);
        item.setMaterialId(materialId);
        item.setCompanyId(po.getCompanyId());
        Material material = new Material();
        material.setId(materialId);
        material.setTenantId("tenant-a");
        material.setCompanyId(po.getCompanyId());
        material.setMaterialGroupId(materialGroupId);
        when(poRepository.findByIdAndIsDeletedFalse(poId)).thenReturn(Optional.of(po));
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("tenant-a", poId))
            .thenReturn(List.of(item));
        when(materialRepository.findByTenantIdAndIdAndIsDeletedFalse("tenant-a", materialId))
            .thenReturn(Optional.of(material));
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(po.getCompanyId());
        PurchaseOrderDispatchReadinessService service = service(
            poRepository, itemRepository, materialRepository, accreditationService, contractService);

        service.evaluate(poId);

        verify(accreditationService).supplierEligibilityBlockedReasonForDemand(
            supplierId, new AccreditationDemand(Set.of(materialGroupId), true, true, false, false),
            "purchase order dispatch");
    }

    @Test
    void serviceOnlyAndWorksOnlyOrdersUseTheirOwnAccreditationScopes() {
        UUID supplierId = UUID.randomUUID();
        PurchaseOrderRepository poRepository = mock(PurchaseOrderRepository.class);
        PurchaseOrderItemRepository itemRepository = mock(PurchaseOrderItemRepository.class);
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        SupplierAccreditationService accreditationService = mock(SupplierAccreditationService.class);
        ContractService contractService = mock(ContractService.class);
        PurchaseOrderDispatchReadinessService service = service(
            poRepository, itemRepository, materialRepository, accreditationService, contractService);
        TenantContext.setTenant("tenant-a");

        UUID servicePoId = UUID.randomUUID();
        PurchaseOrder servicePo = approvedPo(servicePoId, supplierId);
        PurchaseOrderItem serviceLine = validItem(servicePoId);
        serviceLine.setLineType(ProcurementLineType.SERVICE);
        serviceLine.setCompanyId(servicePo.getCompanyId());
        CompanyContext.setCompany(servicePo.getCompanyId());
        when(poRepository.findByIdAndIsDeletedFalse(servicePoId)).thenReturn(Optional.of(servicePo));
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("tenant-a", servicePoId))
            .thenReturn(List.of(serviceLine));

        assertThat(service.evaluate(servicePoId).ready()).isTrue();
        verify(accreditationService).supplierEligibilityBlockedReasonForDemand(
            supplierId, AccreditationDemand.services(), "purchase order dispatch");

        UUID worksPoId = UUID.randomUUID();
        PurchaseOrder worksPo = approvedPo(worksPoId, supplierId);
        worksPo.setCompanyId(servicePo.getCompanyId());
        worksPo.setRequestType(com.enterprise.mm.enums.RequestType.SERVICE);
        PurchaseOrderItem worksLine = validItem(worksPoId);
        worksLine.setLineType(ProcurementLineType.WORK);
        worksLine.setCompanyId(worksPo.getCompanyId());
        when(poRepository.findByIdAndIsDeletedFalse(worksPoId)).thenReturn(Optional.of(worksPo));
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("tenant-a", worksPoId))
            .thenReturn(List.of(worksLine));
        when(contractService.requireWorksContract(null, supplierId, "UZS")).thenReturn(UUID.randomUUID());

        assertThat(service.evaluate(worksPoId).ready()).isTrue();
        verify(accreditationService).supplierEligibilityBlockedReasonForDemand(
            supplierId, AccreditationDemand.works(), "purchase order dispatch");
        verify(contractService).requireWorksContract(null, supplierId, "UZS");
    }

    private PurchaseOrder approvedPo(UUID id, UUID supplierId) {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(id);
        po.setTenantId("tenant-a");
        po.setCompanyId(UUID.randomUUID());
        po.setSupplierId(supplierId);
        po.setStatus(PurchaseOrderStatus.APPROVED.name());
        po.setCurrencyCode("UZS");
        po.setExchangeRate(BigDecimal.ONE);
        po.setExpectedDeliveryDate(LocalDate.now());
        return po;
    }

    private PurchaseOrderItem validItem(UUID poId) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setPurchaseOrderId(poId);
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setQuantity(BigDecimal.ONE);
        item.setUnitPrice(BigDecimal.TEN);
        return item;
    }

    private PurchaseOrderDispatchReadinessService service(
        PurchaseOrderRepository poRepository,
        PurchaseOrderItemRepository itemRepository,
        MaterialRepository materialRepository,
        SupplierAccreditationService accreditationService,
        ContractService contractService
    ) {
        return new PurchaseOrderDispatchReadinessService(
            poRepository,
            itemRepository,
            new SupplierAccreditationDemandResolver(materialRepository),
            accreditationService,
            contractService,
            mock(PurchaseOrderSourceAllocationRepository.class),
            mock(DemandPlanItemPurchaseRequestAllocationRepository.class),
            mock(DemandPlanItemRepository.class)
        );
    }
}
