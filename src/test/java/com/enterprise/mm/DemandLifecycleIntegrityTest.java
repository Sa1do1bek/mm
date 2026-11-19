package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.common.LocalizedText;
import com.enterprise.mm.entity.ConsolidatedDemand;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.DemandPlanItem;
import com.enterprise.mm.entity.DemandPlanningCycle;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.payload.PurchaseOrderCreateRequest;
import com.enterprise.mm.payload.PurchaseOrderItemCreateRequest;
import com.enterprise.mm.enums.ConsolidatedDemandStatus;
import com.enterprise.mm.enums.DemandPeriodType;
import com.enterprise.mm.enums.DemandPlanItemStatus;
import com.enterprise.mm.enums.DemandPlanStatus;
import com.enterprise.mm.enums.DemandPlanningCycleStatus;
import com.enterprise.mm.enums.DemandPriority;
import com.enterprise.mm.enums.DemandType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.ConsolidatedDemandCreateRequest;
import com.enterprise.mm.payload.ConsolidatedDemandUpdateRequest;
import com.enterprise.mm.payload.DemandPlanCreateRequest;
import com.enterprise.mm.payload.DemandPlanItemCreateRequest;
import com.enterprise.mm.payload.DemandPlanItemResponse;
import com.enterprise.mm.payload.DemandPlanItemUpdateRequest;
import com.enterprise.mm.payload.DemandPlanResponse;
import com.enterprise.mm.payload.DemandPlanUpdateRequest;
import com.enterprise.mm.repository.ConsolidatedDemandRepository;
import com.enterprise.mm.repository.DemandPlanItemRepository;
import com.enterprise.mm.repository.DemandPlanItemPurchaseRequestAllocationRepository;
import com.enterprise.mm.repository.DemandPlanRepository;
import com.enterprise.mm.repository.DemandPlanningCycleRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseOrderSourceAllocationRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.ConsolidatedDemandService;
import com.enterprise.mm.service.DemandPlanItemService;
import com.enterprise.mm.service.DemandPlanService;
import com.enterprise.mm.service.PurchaseOrderService;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "demand-integrity", roles = "ADMIN")
class DemandLifecycleIntegrityTest {
    @Autowired DemandPlanService demandPlanService;
    @Autowired DemandPlanItemService demandPlanItemService;
    @Autowired ConsolidatedDemandService consolidatedDemandService;
    @Autowired DemandPlanRepository demandPlanRepository;
    @Autowired DemandPlanningCycleRepository demandPlanningCycleRepository;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired DemandPlanItemRepository demandPlanItemRepository;
    @Autowired DemandPlanItemPurchaseRequestAllocationRepository demandPlanItemPurchaseRequestAllocationRepository;
    @Autowired ConsolidatedDemandRepository consolidatedDemandRepository;
    @Autowired MaterialRepository materialRepository;
    @Autowired PurchaseRequestRepository purchaseRequestRepository;
    @Autowired PurchaseRequestItemRepository purchaseRequestItemRepository;
    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired PurchaseOrderSourceAllocationRepository purchaseOrderSourceAllocationRepository;
    @Autowired SupplierRepository supplierRepository;

    @Test
    void consolidatedDemandDefaultsToOpen() {
        DemandPlanResponse plan = createPlan();

        var created = consolidatedDemandService.create(consolidatedDemandRequest(plan, material()));

        assertThat(created.getStatus()).isEqualTo(ConsolidatedDemandStatus.OPEN.name());
    }

    @Test
    void consolidatedDemandUpdatePreservesComputedFields() {
        DemandPlanResponse plan = createPlan();
        Material material = material();
        ConsolidatedDemandCreateRequest create = consolidatedDemandRequest(plan, material);
        create.setTotalRequestedQuantity(new BigDecimal("10"));
        create.setTotalApprovedQuantity(new BigDecimal("8"));
        create.setTotalEstimatedAmount(new BigDecimal("800"));
        create.setSourceItemCount(2);
        create.setPurchaseRequestId(purchaseRequest(plan).getId());
        create.setConsolidatedAt(LocalDateTime.now().minusDays(1));
        var original = consolidatedDemandService.create(create);

        ConsolidatedDemandUpdateRequest update = consolidatedDemandUpdate(plan, material);
        update.setStatus(ConsolidatedDemandStatus.CONVERTED_TO_PR.name());
        update.setTotalRequestedQuantity(BigDecimal.ONE);
        update.setTotalApprovedQuantity(BigDecimal.ONE);
        update.setTotalEstimatedAmount(BigDecimal.ONE);
        update.setSourceItemCount(99);
        update.setPurchaseRequestId(UUID.randomUUID());
        update.setConsolidatedAt(LocalDateTime.now());

        var saved = consolidatedDemandService.update(original.getId(), update);

        assertThat(saved.getStatus()).isEqualTo(ConsolidatedDemandStatus.OPEN.name());
        assertThat(saved.getTotalRequestedQuantity()).isEqualByComparingTo("10");
        assertThat(saved.getTotalApprovedQuantity()).isEqualByComparingTo("8");
        assertThat(saved.getTotalEstimatedAmount()).isEqualByComparingTo("800");
        assertThat(saved.getSourceItemCount()).isEqualTo(2);
        assertThat(saved.getHighestPriority()).isEqualTo(DemandPriority.NORMAL.name());
        assertThat(saved.getPurchaseRequestId()).isEqualTo(original.getPurchaseRequestId());
        assertThat(saved.getConsolidatedAt()).isEqualToIgnoringNanos(original.getConsolidatedAt());
    }

    @Test
    void consolidatedDemandMutationRequiresOpenStatus() {
        DemandPlanResponse plan = createPlan();
        Material material = material();
        var created = consolidatedDemandService.create(consolidatedDemandRequest(plan, material));
        ConsolidatedDemand entity = consolidatedDemandRepository.findAnyById(created.getId()).orElseThrow();
        entity.setStatus(ConsolidatedDemandStatus.CONVERTED_TO_PR.name());
        ConsolidatedDemand converted = consolidatedDemandRepository.saveAndFlush(entity);

        assertThatThrownBy(() -> consolidatedDemandService.update(created.getId(), consolidatedDemandUpdate(plan, material)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("OPEN");
        assertThatThrownBy(() -> consolidatedDemandService.softDelete(created.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("OPEN");

        converted.softDelete();
        consolidatedDemandRepository.saveAndFlush(converted);
        assertThatThrownBy(() -> consolidatedDemandService.restore(created.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("OPEN");
    }

    @Test
    void demandPlanUpdateRequiresDraftOrCollecting() {
        DemandPlanResponse plan = createPlan();
        DemandPlan entity = demandPlanRepository.findAnyById(plan.getId()).orElseThrow();
        entity.setStatus(DemandPlanStatus.APPROVED.name());
        entity.setApprovedAt(LocalDateTime.now().minusDays(1));
        demandPlanRepository.saveAndFlush(entity);

        assertThatThrownBy(() -> demandPlanService.update(plan.getId(), demandPlanUpdate(plan)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("DRAFT or COLLECTING");
    }

    @Test
    void demandPlanUpdatePreservesLifecycleFields() {
        DemandPlanResponse plan = createPlan();
        DemandPlanUpdateRequest update = demandPlanUpdate(plan);
        update.setStatus(DemandPlanStatus.APPROVED.name());
        update.setApprovedAt(LocalDateTime.now());
        update.setConsolidatedAt(LocalDateTime.now());

        var saved = demandPlanService.update(plan.getId(), update);

        assertThat(saved.getStatus()).isEqualTo(DemandPlanStatus.DRAFT.name());
        assertThat(saved.getApprovedAt()).isNull();
        assertThat(saved.getConsolidatedAt()).isNull();
    }

    @Test
    void demandPlanNameIsGeneratedFromDepartmentAndYearAndIgnoresClientName() {
        Department department = department("Electrical Energy Department");
        DemandPlanCreateRequest request = planRequest(department);
        request.setName("client supplied name must be ignored");
        request.setPlanYear(2026);
        request.setPeriodType(DemandPeriodType.QUARTER.name());
        request.setQuarter(3);

        DemandPlanResponse created = demandPlanService.create(request);

        assertThat(LocalizedText.translate(created.getName(), Locale.ENGLISH))
            .isEqualTo("Demand Plan - Electrical Energy Departmentning 2026-yil uchun arizasi");
        assertThat(created.getName()).doesNotContain(department.getId().toString());
        assertThat(LocalizedText.translate(created.getName(), Locale.ENGLISH))
            .doesNotContain("Q3")
            .doesNotContain("quarter")
            .doesNotContain("квартал");

        DemandPlanUpdateRequest update = demandPlanUpdate(created);
        update.setName("attempted update override");
        update.setPlanYear(2027);
        DemandPlanResponse updated = demandPlanService.update(created.getId(), update);

        assertThat(LocalizedText.translate(updated.getName(), Locale.ENGLISH))
            .isEqualTo("Demand Plan - Electrical Energy Departmentning 2027-yil uchun arizasi");
    }

    @Test
    void collectingDemandPlanRejectsNestedReplacementAfterChildLifecycleAdvances() {
        DemandPlanResponse plan = createPlan();
        Material material = material();
        DemandPlanItemResponse submitted = demandPlanItemService.create(itemRequest(plan, material, "10", "8", "2"));
        demandPlanItemService.submit(submitted.getId());
        demandPlanService.startCollection(plan.getId());
        DemandPlanUpdateRequest update = demandPlanUpdate(plan);
        update.setItems(java.util.List.of(itemRequest(plan, material, "5", "5", "0")));

        assertThatThrownBy(() -> demandPlanService.update(plan.getId(), update))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("nested items");

        DemandPlanItem preserved = demandPlanItemRepository.findAnyById(submitted.getId()).orElseThrow();
        assertThat(preserved.isDeleted()).isFalse();
        assertThat(preserved.getStatus()).isEqualTo(DemandPlanItemStatus.SUBMITTED.name());
    }

    @Test
    void demandPlanItemUpdateRequiresDraftItemAndEditableParent() {
        DemandPlanResponse plan = createPlan();
        Material material = material();
        DemandPlanItemResponse submitted = demandPlanItemService.create(itemRequest(plan, material, "10", "8", "2"));
        demandPlanItemService.submit(submitted.getId());

        assertThatThrownBy(() -> demandPlanItemService.update(submitted.getId(), itemUpdate(plan, material)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("DRAFT");

        DemandPlanItemResponse draft = demandPlanItemService.create(itemRequest(plan, material, "10", "8", "2"));
        DemandPlan parent = demandPlanRepository.findAnyById(plan.getId()).orElseThrow();
        parent.setStatus(DemandPlanStatus.CLOSED.name());
        demandPlanRepository.saveAndFlush(parent);

        assertThatThrownBy(() -> demandPlanItemService.update(draft.getId(), itemUpdate(plan, material)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("parent demand plan");
    }

    @Test
    void demandPlanItemUpdatePreservesSystemOwnedFields() {
        DemandPlanResponse plan = createPlan();
        Material material = material();
        DemandPlanItemResponse created = demandPlanItemService.create(itemRequest(plan, material, "10", "8", "2"));
        UUID consolidatedDemandId = consolidatedDemandService.create(consolidatedDemandRequest(plan, material)).getId();
        PurchaseRequest purchaseRequest = purchaseRequest(plan);
        PurchaseRequestItem purchaseRequestItem = new PurchaseRequestItem();
        purchaseRequestItem.setCode("PRI-INTEGRITY-" + suffix());
        purchaseRequestItem.setName("Purchase request item integrity");
        purchaseRequestItem.setStatus("OPEN");
        purchaseRequestItem.setPurchaseRequestId(purchaseRequest.getId());
        purchaseRequestItem.setMaterialId(material.getId());
        purchaseRequestItem.setQuantity(BigDecimal.TEN);
        purchaseRequestItem.setEstimatedPrice(BigDecimal.TEN);
        UUID purchaseRequestItemId = purchaseRequestItemRepository.save(purchaseRequestItem).getId();
        DemandPlanItem entity = demandPlanItemRepository.findAnyById(created.getId()).orElseThrow();
        entity.setConsolidatedDemandId(consolidatedDemandId);
        entity.setPurchaseRequestItemId(purchaseRequestItemId);
        demandPlanItemRepository.saveAndFlush(entity);

        DemandPlanItemUpdateRequest update = itemUpdate(plan, material);
        update.setApprovedQuantity(BigDecimal.ONE);
        update.setReservedQuantity(BigDecimal.ONE);
        update.setConsolidatedDemandId(UUID.randomUUID());
        update.setPurchaseRequestItemId(UUID.randomUUID());

        var saved = demandPlanItemService.update(created.getId(), update);

        assertThat(saved.getApprovedQuantity()).isEqualByComparingTo("8");
        assertThat(saved.getReservedQuantity()).isEqualByComparingTo("2");
        assertThat(saved.getConsolidatedDemandId()).isEqualTo(consolidatedDemandId);
        assertThat(saved.getPurchaseRequestItemId()).isEqualTo(purchaseRequestItemId);
    }

    @Test
    void demandPlanItemCreateRejectsApprovedAndReservedQuantitiesOutsideRequestedBounds() {
        DemandPlanResponse plan = createPlan();
        Material material = material();

        assertInvalidQuantity(itemRequest(plan, material, "10", "-1", "0"), "Approved quantity");
        assertInvalidQuantity(itemRequest(plan, material, "10", "11", "0"), "Approved quantity");
        assertInvalidQuantity(itemRequest(plan, material, "10", "8", "-1"), "Reserved quantity");
        assertInvalidQuantity(itemRequest(plan, material, "10", "8", "11"), "Reserved quantity");
    }

    @Test
    void demandPlanItemCreatePreservesExplicitZeroApprovedQuantity() {
        DemandPlanResponse plan = createPlan();
        Material material = material();

        DemandPlanItemResponse created = demandPlanItemService.create(itemRequest(plan, material, "10", "0", "0"));

        assertThat(created.getApprovedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(created.getEstimatedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void demandPlanItemCreateDefaultsApprovedQuantityToZeroUntilApproval() {
        DemandPlanResponse plan = createPlan();
        Material material = material();
        DemandPlanItemCreateRequest request = itemRequest(plan, material, "10", "8", "0");
        request.setApprovedQuantity(null);

        DemandPlanItemResponse created = demandPlanItemService.create(request);

        assertThat(created.getStatus()).isEqualTo(DemandPlanItemStatus.DRAFT.name());
        assertThat(created.getApprovedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(created.getEstimatedAmount()).isEqualByComparingTo("100");

        DemandPlanItemResponse approved = demandPlanItemService.approve(created.getId(), "approved full requested quantity");

        assertThat(approved.getStatus()).isEqualTo(DemandPlanItemStatus.APPROVED.name());
        assertThat(approved.getApprovedQuantity()).isEqualByComparingTo("10");
        assertThat(approved.getEstimatedAmount()).isEqualByComparingTo("100");
    }

    @Test
    void purchaseRequestCreatedFromDemandPlanReusesParentBusinessNameAndDescription() {
        DemandPlanResponse plan = createPlan();
        Material material = material();
        DemandPlanItemResponse item = demandPlanItemService.create(itemRequest(plan, material, "10", "8", "0"));
        DemandPlan parent = demandPlanRepository.findAnyById(plan.getId()).orElseThrow();
        parent.setName("Chemical Procurement for Production");
        parent.setDescription("Production department needs chemicals for daily operations.");
        parent.setStatus(DemandPlanStatus.APPROVED.name());
        demandPlanRepository.saveAndFlush(parent);
        DemandPlanItem source = demandPlanItemRepository.findAnyById(item.getId()).orElseThrow();
        source.setStatus(DemandPlanItemStatus.APPROVED.name());
        demandPlanItemRepository.saveAndFlush(source);

        demandPlanService.createPurchaseRequests(plan.getId());

        List<PurchaseRequest> requests = purchaseRequestRepository.findAll().stream()
            .filter(request -> plan.getId().equals(request.getDemandPlanId()))
            .toList();
        assertThat(requests).hasSize(1);
        assertThat(LocalizedText.translate(requests.get(0).getName(), Locale.ENGLISH))
            .isEqualTo("Purchase Request - Chemical Procurement for Production");
        assertThat(requests.get(0).getDescription()).isEqualTo(parent.getDescription());
        var allocations = demandPlanItemPurchaseRequestAllocationRepository
            .findByTenantIdAndCompanyIdAndDemandPlanItemIdInAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), List.of(item.getId()));
        assertThat(allocations).hasSize(1);
        assertThat(allocations.getFirst().getPurchaseRequestItemId()).isNotNull();
        assertThat(allocations.getFirst().getAllocatedQuantity()).isEqualByComparingTo("8");
    }

    @Test
    void duplicateDemandPlanConversionReturnsTheExistingPurchaseRequest() {
        DemandPlanResponse plan = createPlan();
        Material material = material();
        DemandPlanItemResponse item = demandPlanItemService.create(itemRequest(plan, material, "10", "8", "0"));
        DemandPlan parent = demandPlanRepository.findAnyById(plan.getId()).orElseThrow();
        parent.setStatus(DemandPlanStatus.APPROVED.name());
        demandPlanRepository.saveAndFlush(parent);
        DemandPlanItem source = demandPlanItemRepository.findAnyById(item.getId()).orElseThrow();
        source.setStatus(DemandPlanItemStatus.APPROVED.name());
        demandPlanItemRepository.saveAndFlush(source);

        var first = demandPlanService.createPurchaseRequests(plan.getId());
        var replay = demandPlanService.createPurchaseRequests(plan.getId());

        assertThat(replay.purchaseRequestIds()).containsExactlyElementsOf(first.purchaseRequestIds());
        assertThat(purchaseRequestRepository.findByTenantIdAndCompanyIdAndDemandPlanIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), plan.getId()
        )).hasSize(1);
    }

    @Test
    void purchaseRequestCreatedFromGeneratedDemandPlanKeepsFlattenedParentName() {
        Department department = department("Electrical Energy Department");
        DemandPlanCreateRequest request = planRequest(department);
        request.setPlanYear(2026);
        DemandPlanResponse plan = demandPlanService.create(request);
        Material material = material();
        DemandPlanItemResponse item = demandPlanItemService.create(itemRequest(plan, material, "10", "8", "0"));
        DemandPlan parent = demandPlanRepository.findAnyById(plan.getId()).orElseThrow();
        parent.setStatus(DemandPlanStatus.APPROVED.name());
        demandPlanRepository.saveAndFlush(parent);
        DemandPlanItem source = demandPlanItemRepository.findAnyById(item.getId()).orElseThrow();
        source.setStatus(DemandPlanItemStatus.APPROVED.name());
        demandPlanItemRepository.saveAndFlush(source);

        demandPlanService.createPurchaseRequests(plan.getId());

        List<PurchaseRequest> requests = purchaseRequestRepository.findAll().stream()
            .filter(purchaseRequest -> plan.getId().equals(purchaseRequest.getDemandPlanId()))
            .toList();
        assertThat(requests).hasSize(1);
        assertThat(LocalizedText.translate(requests.getFirst().getName(), Locale.ENGLISH))
            .isEqualTo("Purchase Request - Electrical Energy Departmentning 2026-yil uchun arizasi");
    }

    @Test
    void directPurchaseOrderCreationPersistsSourceAllocation() {
        DemandPlanResponse plan = createPlan();
        approvePlanningCycleFor(plan);
        Material material = material();
        PurchaseRequest purchaseRequest = purchaseRequest(plan);
        PurchaseRequestItem purchaseRequestItem = purchaseRequestItem(purchaseRequest, material, "4");
        PurchaseOrderCreateRequest request = new PurchaseOrderCreateRequest();
        request.setCode("PO-INTEGRITY-" + suffix());
        request.setName("Purchase order lineage integrity");
        request.setPurchaseRequestId(purchaseRequest.getId());
        request.setSupplierId(supplier().getId());
        request.setCurrencyCode("UZS");
        PurchaseOrderItemCreateRequest item = new PurchaseOrderItemCreateRequest();
        item.setCode("POI-INTEGRITY-" + suffix());
        item.setName("Purchase order item lineage integrity");
        item.setMaterialId(material.getId());
        item.setQuantity(new BigDecimal("4"));
        item.setUnit("EA");
        item.setUnitPrice(BigDecimal.TEN);
        item.setPurchaseRequestItemId(purchaseRequestItem.getId());
        request.setItems(java.util.List.of(item));

        purchaseOrderService.create(request);

        var allocations = purchaseOrderSourceAllocationRepository
            .findByTenantIdAndCompanyIdAndPurchaseRequestItemIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), purchaseRequestItem.getId());
        assertThat(allocations).hasSize(1);
        assertThat(allocations.get(0).getOrderedQuantity()).isEqualByComparingTo("4");
        assertThat(allocations.get(0).getPurchaseOrderItemId()).isNotNull();
    }

    @Test
    void prBackedPurchaseOrderRequiresLineSourceAllocation() {
        DemandPlanResponse plan = createPlan();
        approvePlanningCycleFor(plan);
        Material material = material();
        PurchaseRequest purchaseRequest = purchaseRequest(plan);
        purchaseRequestItem(purchaseRequest, material, "4");
        PurchaseOrderCreateRequest request = new PurchaseOrderCreateRequest();
        request.setCode("PO-MISSING-LINEAGE-" + suffix());
        request.setName("Purchase order missing lineage");
        request.setPurchaseRequestId(purchaseRequest.getId());
        request.setSupplierId(supplier().getId());
        request.setCurrencyCode("UZS");
        PurchaseOrderItemCreateRequest item = new PurchaseOrderItemCreateRequest();
        item.setCode("POI-MISSING-LINEAGE-" + suffix());
        item.setName("Purchase order item missing lineage");
        item.setMaterialId(material.getId());
        item.setQuantity(new BigDecimal("4"));
        item.setUnit("EA");
        item.setUnitPrice(BigDecimal.TEN);
        request.setItems(java.util.List.of(item));

        assertThatThrownBy(() -> purchaseOrderService.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("purchaseRequestItemId is required");
    }

    private void assertInvalidQuantity(DemandPlanItemCreateRequest request, String field) {
        assertThatThrownBy(() -> demandPlanItemService.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(field);
    }

    private DemandPlanResponse createPlan() {
        return demandPlanService.create(planRequest(department("Demand lifecycle integrity department")));
    }

    private DemandPlanCreateRequest planRequest(Department department) {
        DemandPlanCreateRequest request = new DemandPlanCreateRequest();
        request.setCode("DP-INTEGRITY-" + suffix());
        request.setName("Demand lifecycle integrity");
        request.setPlanYear(Year.now().getValue());
        request.setPeriodType(DemandPeriodType.YEAR.name());
        request.setDepartmentId(department.getId());
        request.setOwnerUsername("demand-integrity");
        request.setCurrencyCode("UZS");
        return request;
    }

    private Department department(String name) {
        Department department = new Department();
        department.setCode("DEP-INTEGRITY-" + suffix());
        department.setName(name);
        department.setStatus("ACTIVE");
        return departmentRepository.saveAndFlush(department);
    }

    private void approvePlanningCycleFor(DemandPlanResponse plan) {
        DemandPlanningCycle cycle = new DemandPlanningCycle();
        cycle.setCode("DPC-INTEGRITY-" + suffix());
        cycle.setName("Demand planning cycle integrity");
        cycle.setStatus(DemandPlanningCycleStatus.BUDGET_APPROVED.name());
        cycle.setPlanYear(plan.getPlanYear());
        cycle.setPeriodType(plan.getPeriodType());
        cycle.setQuarter(plan.getQuarter());
        cycle.setPeriodStart(plan.getPeriodStart());
        cycle.setPeriodEnd(plan.getPeriodEnd());
        cycle.setCurrencyCode(plan.getCurrencyCode());
        cycle.setOwnerUsername(plan.getOwnerUsername());
        DemandPlanningCycle savedCycle = demandPlanningCycleRepository.saveAndFlush(cycle);

        DemandPlan parent = demandPlanRepository.findAnyById(plan.getId()).orElseThrow();
        parent.setPlanningCycle(savedCycle);
        parent.setStatus(DemandPlanStatus.APPROVED.name());
        demandPlanRepository.saveAndFlush(parent);
    }

    @Autowired com.enterprise.mm.repository.UnitOfMeasureRepository stockUnits;

    private Material material() {
        Material material = new Material();
        var unit=new com.enterprise.mm.entity.UnitOfMeasure();unit.setCode("EA-"+suffix());unit.setName("Each");unit.setMeasurementNameShort("EA");unit.setUnitType(com.enterprise.mm.enums.MeasurementUnitType.QUANTITY);unit=stockUnits.saveAndFlush(unit);
        material.setUnitOfMeasureId(unit.getId());material.setUnitOfMeasure(unit);
        material.setCode("MAT-INTEGRITY-" + suffix());
        material.setName(material.getCode());
        material.setStatus("ACTIVE");
        return materialRepository.save(material);
    }

    private Supplier supplier() {
        Supplier supplier = new Supplier();
        supplier.setCode("SUP-INTEGRITY-" + suffix());
        supplier.setName("Supplier integrity");
        supplier.setLegalName("Supplier integrity LLC");
        supplier.setShortName("Supplier integrity");
        supplier.setStatus("ACTIVE");
        return supplierRepository.save(supplier);
    }

    private ConsolidatedDemandCreateRequest consolidatedDemandRequest(DemandPlanResponse plan, Material material) {
        ConsolidatedDemandCreateRequest request = new ConsolidatedDemandCreateRequest();
        request.setCode("CD-INTEGRITY-" + suffix());
        request.setName("Consolidated lifecycle integrity");
        request.setDemandPlanId(plan.getId());
        request.setMaterialId(material.getId());
        request.setPeriodStart(plan.getPeriodStart());
        request.setPeriodEnd(plan.getPeriodEnd());
        request.setHighestPriority(DemandPriority.NORMAL.name());
        request.setTotalRequestedQuantity(BigDecimal.TEN);
        request.setTotalApprovedQuantity(BigDecimal.TEN);
        request.setTotalEstimatedAmount(BigDecimal.valueOf(100));
        request.setCurrencyCode("UZS");
        request.setSourceItemCount(1);
        return request;
    }

    private ConsolidatedDemandUpdateRequest consolidatedDemandUpdate(DemandPlanResponse plan, Material material) {
        ConsolidatedDemandUpdateRequest request = new ConsolidatedDemandUpdateRequest();
        request.setCode("CD-UPDATED-" + suffix());
        request.setName("Updated consolidated lifecycle integrity");
        request.setDemandPlanId(plan.getId());
        request.setMaterialId(material.getId());
        request.setPeriodStart(plan.getPeriodStart());
        request.setPeriodEnd(plan.getPeriodEnd());
        request.setHighestPriority(DemandPriority.HIGH.name());
        request.setTotalRequestedQuantity(BigDecimal.TEN);
        request.setTotalApprovedQuantity(BigDecimal.TEN);
        request.setTotalEstimatedAmount(BigDecimal.valueOf(100));
        request.setCurrencyCode("UZS");
        request.setSourceItemCount(1);
        return request;
    }

    private PurchaseRequest purchaseRequest(DemandPlanResponse plan) {
        PurchaseRequest request = new PurchaseRequest();
        request.setCode("PR-INTEGRITY-" + suffix());
        request.setName("Purchase request integrity");
        request.setStatus("DRAFT");
        request.setDemandPlanId(plan.getId());
        request.setRequestedBy("demand-integrity");
        request.setRequiredDate(plan.getPeriodStart().plusDays(30));
        request.setTotalAmount(BigDecimal.valueOf(100));
        request.setCurrencyCode("UZS");
        return purchaseRequestRepository.save(request);
    }

    private PurchaseRequestItem purchaseRequestItem(PurchaseRequest purchaseRequest, Material material, String quantity) {
        PurchaseRequestItem item = new PurchaseRequestItem();
        item.setCode("PRI-INTEGRITY-" + suffix());
        item.setName("Purchase request item integrity");
        item.setStatus("OPEN");
        item.setPurchaseRequestId(purchaseRequest.getId());
        item.setMaterialId(material.getId());
        item.setQuantity(new BigDecimal(quantity));
        item.setEstimatedPrice(BigDecimal.TEN);
        return purchaseRequestItemRepository.save(item);
    }

    private DemandPlanUpdateRequest demandPlanUpdate(DemandPlanResponse plan) {
        DemandPlanUpdateRequest request = new DemandPlanUpdateRequest();
        request.setCode(plan.getCode());
        request.setName("Updated demand lifecycle integrity");
        request.setRequestType(plan.getRequestType());
        request.setPlanYear(plan.getPlanYear());
        request.setPeriodType(plan.getPeriodType());
        request.setQuarter(plan.getQuarter());
        request.setOwnerUsername(plan.getOwnerUsername());
        request.setCurrencyCode(plan.getCurrencyCode());
        return request;
    }

    private DemandPlanItemCreateRequest itemRequest(
        DemandPlanResponse plan,
        Material material,
        String requested,
        String approved,
        String reserved
    ) {
        DemandPlanItemCreateRequest request = new DemandPlanItemCreateRequest();
        request.setCode("DPI-INTEGRITY-" + suffix());
        request.setName("Demand item lifecycle integrity");
        request.setDemandPlanId(plan.getId());
        request.setMaterialId(material.getId());
        request.setDemandType(DemandType.PROJECT.name());
        request.setPriority(DemandPriority.NORMAL.name());
        request.setRequestedQuantity(new BigDecimal(requested));
        request.setApprovedQuantity(new BigDecimal(approved));
        request.setReservedQuantity(new BigDecimal(reserved));
        request.setEstimatedUnitPrice(BigDecimal.TEN);
        request.setRequiredDate(plan.getPeriodStart().plusDays(10));
        request.setCurrencyCode("UZS");
        return request;
    }

    private DemandPlanItemUpdateRequest itemUpdate(DemandPlanResponse plan, Material material) {
        DemandPlanItemUpdateRequest request = new DemandPlanItemUpdateRequest();
        request.setCode("DPI-UPDATED-" + suffix());
        request.setName("Updated demand item lifecycle integrity");
        request.setDemandPlanId(plan.getId());
        request.setMaterialId(material.getId());
        request.setDemandType(DemandType.PROJECT.name());
        request.setPriority(DemandPriority.HIGH.name());
        request.setRequestedQuantity(BigDecimal.TEN);
        request.setApprovedQuantity(BigDecimal.TEN);
        request.setReservedQuantity(BigDecimal.ZERO);
        request.setEstimatedUnitPrice(BigDecimal.valueOf(20));
        request.setRequiredDate(plan.getPeriodStart().plusDays(20));
        request.setCurrencyCode("UZS");
        return request;
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
