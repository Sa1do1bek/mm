package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.payload.DemandManagementDtos.DemandReservationRequest;
import com.enterprise.mm.payload.DemandManagementDtos.DemandCyclePlanGenerationRequest;
import com.enterprise.mm.payload.DemandManagementDtos.ReleaseReservationRequest;
import com.enterprise.mm.payload.DemandPlanningCycleResponse;
import com.enterprise.mm.payload.DemandPlanItemCreateRequest;
import com.enterprise.mm.payload.DemandPlanResponse;
import com.enterprise.mm.payload.DemandPlanItemResponse;
import com.enterprise.mm.payload.DemandPlanCreateRequest;
import com.enterprise.mm.payload.DemandPlanningCycleCreateRequest;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.LocalizedText;
import com.enterprise.mm.service.DemandPlanItemService;
import com.enterprise.mm.service.DemandPlanService;
import com.enterprise.mm.service.DemandPlanningCycleService;
import com.enterprise.mm.service.MaterialReservationService;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.ApprovalStepType;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.enums.DemandPeriodType;
import com.enterprise.mm.enums.DemandPlanItemStatus;
import com.enterprise.mm.enums.DemandPlanStatus;
import com.enterprise.mm.enums.DemandPlanningCycleStatus;
import com.enterprise.mm.enums.DemandPriority;
import com.enterprise.mm.enums.DemandType;
import com.enterprise.mm.enums.MaterialReservationStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.ApprovalRequestType;
import com.enterprise.mm.entity.ApprovalFlow;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.entity.BudgetItem;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.DemandPlanItem;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.repository.ApprovalFlowRepository;
import com.enterprise.mm.repository.ApprovalFlowStepRepository;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.BudgetItemRepository;
import com.enterprise.mm.repository.ConsolidatedDemandRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.DemandPlanItemRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.DemandPlanApprovalAdapter;
import com.enterprise.mm.service.approval.DemandPlanningCycleApprovalAdapter;
import com.enterprise.mm.service.approval.PurchaseRequestApprovalAdapter;
import com.enterprise.mm.service.PurchaseRequestService;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalTaskDecisionRequest;
import com.enterprise.mm.payload.ApprovalDtos.DemandStockReviewLineDecision;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "planner", roles = "ADMIN")
class DemandManagementTest {
    @Autowired DemandPlanningCycleService demandPlanningCycleService;
    @Autowired DemandPlanService demandPlanService;
    @Autowired DemandPlanItemService demandPlanItemService;
    @Autowired MaterialReservationService materialReservationService;
    @Autowired PurchaseRequestService purchaseRequestService;
    @Autowired MaterialRepository materialRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired StockBalanceRepository stockBalanceRepository;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired BudgetItemRepository budgetItemRepository;
    @Autowired ConsolidatedDemandRepository consolidatedDemandRepository;
    @Autowired DemandPlanItemRepository demandPlanItemRepository;
    @Autowired PurchaseRequestItemRepository purchaseRequestItemRepository;
    @Autowired ApprovalFlowRepository approvalFlowRepository;
    @Autowired ApprovalFlowStepRepository approvalFlowStepRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired UnitOfMeasureRepository unitOfMeasureRepository;
    @Autowired ApprovalEngineService approvalEngineService;

    @BeforeEach
    void configureApprovalFlows() {
        ensureApprovalFlow(DemandPlanApprovalAdapter.MODULE_KEY, "Test demand plan approval flow", "TEST-DP-STEP", "ADMIN", RequestType.GOODS);
        ensureApprovalFlow(DemandPlanningCycleApprovalAdapter.MODULE_KEY, "Test demand planning cycle approval flow", "TEST-DPC-STEP", "ADMIN", null);
        ensureApprovalFlow(PurchaseRequestApprovalAdapter.MODULE_KEY, "Test PR approval flow", "TEST-PR-STEP", "ADMIN", RequestType.GOODS);
    }

    @Test
    void demandPlanApprovalFundsLinkedBudgetItemForGeneratedPurchaseRequest() {
        Material material = material("MAT-DEMAND-BUDGET-" + UUID.randomUUID().toString().substring(0, 8));
        BudgetItem budgetItem = budgetItem("BI-DEMAND-ZERO-" + UUID.randomUUID().toString().substring(0, 8), BigDecimal.ZERO);

        DemandPlanCreateRequest planRequest = new DemandPlanCreateRequest();
        planRequest.setCode("DP-BUDGET-" + UUID.randomUUID().toString().substring(0, 8));
        planRequest.setName("Budget-backed demand plan");
        planRequest.setPlanYear(Year.now().getValue());
        planRequest.setPeriodType(DemandPeriodType.YEAR.name());
        planRequest.setDepartmentId(department("DEP-BUDGET-" + UUID.randomUUID().toString().substring(0, 8)).getId());
        planRequest.setBudgetItemId(budgetItem.getId());
        planRequest.setOwnerUsername("planner");
        planRequest.setCurrencyCode("UZS");
        DemandPlanResponse plan = demandPlanService.create(planRequest);

        demandPlanItemService.create(demandItem(
            plan,
            material,
            DemandType.PROJECT.name(),
            DemandPriority.HIGH.name(),
            null,
            1,
            300_000
        ));

        assertThat(demandPlanService.submit(plan.getId()).getStatus()).isEqualTo(DemandPlanStatus.SUBMITTED.name());
        approveAll(DemandPlanApprovalAdapter.MODULE_KEY, plan.getId());

        BudgetItem fundedBudget = budgetItemRepository.findAnyById(budgetItem.getId()).orElseThrow();
        assertThat(fundedBudget.getAnnualLimit()).isEqualByComparingTo("300000");

        var prResult = demandPlanService.createPurchaseRequests(plan.getId());

        assertThat(purchaseRequestService.submit(prResult.purchaseRequestIds().getFirst()).getStatus())
            .isEqualTo(com.enterprise.mm.enums.PurchaseRequestStatus.APPROVAL_PENDING.name());
    }

    @Test
    void planningCycleGeneratesDepartmentPlansControlsBudgetGateAndTracksReservations() {
        int nextYear = Year.now().plusYears(1).getValue();
        UUID maintenanceDepartment = department("DEP-MAINT-" + UUID.randomUUID().toString().substring(0, 8)).getId();
        UUID productionDepartment = department("DEP-PROD-" + UUID.randomUUID().toString().substring(0, 8)).getId();
        UUID budgetItemId = budgetItem("BI-DEMAND-" + UUID.randomUUID().toString().substring(0, 8)).getId();
        Material material = material("MAT-DEMAND-CYCLE-1");
        Warehouse warehouse = warehouse("WH-DEMAND-CYCLE-1");
        balance(material, warehouse, BigDecimal.valueOf(50));

        DemandPlanningCycleCreateRequest cycleRequest = new DemandPlanningCycleCreateRequest();
        cycleRequest.setCode("DPC-" + nextYear);
        cycleRequest.setName("Годовой цикл потребности " + nextYear);
        cycleRequest.setPlanYear(nextYear);
        cycleRequest.setPeriodType(DemandPeriodType.YEAR.name());
        cycleRequest.setCurrencyCode("UZS");
        DemandPlanningCycleResponse cycle = demandPlanningCycleService.create(cycleRequest);

        assertThat(demandPlanningCycleService.startCollection(cycle.getId()).getStatus()).isEqualTo(DemandPlanningCycleStatus.COLLECTING.name());
        var generated = demandPlanningCycleService.generatePlans(
            cycle.getId(),
            new DemandCyclePlanGenerationRequest(List.of(maintenanceDepartment, productionDepartment), budgetItemId, "planner")
        );
        assertThat(generated.createdCount()).isEqualTo(2);
        assertThat(demandPlanningCycleService.generatePlans(
            cycle.getId(),
            new DemandCyclePlanGenerationRequest(List.of(maintenanceDepartment, productionDepartment), budgetItemId, "planner")
        ).existingCount()).isEqualTo(2);

        DemandPlanResponse maintenancePlan = demandPlanService.get(generated.demandPlanIds().getFirst());
        assertThat(maintenancePlan.getPlanningCycleId()).isEqualTo(cycle.getId());
        assertThat(maintenancePlan.getPlanYear()).isEqualTo(nextYear);
        assertThat(maintenancePlan.getPeriodStart()).isEqualTo(LocalDate.of(nextYear, 1, 1));
        assertThat(LocalizedText.translate(maintenancePlan.getName(), Locale.ENGLISH))
            .isEqualTo("Demand Plan - " + LocalizedText.translate(maintenancePlan.getDepartmentName(), Locale.ENGLISH)
                + "ning " + nextYear + "-yil uchun arizasi")
            .doesNotContain(maintenanceDepartment.toString())
            .doesNotContain("quarter")
            .doesNotContain("квартал");

        DemandPlanItemCreateRequest itemRequest = demandItem(maintenancePlan, material, DemandType.PROJECT.name(), DemandPriority.CRITICAL.name(), null, 12, 200);
        itemRequest.setRequiredDate(LocalDate.of(nextYear, 2, 5));
        DemandPlanItemResponse item = demandPlanItemService.create(itemRequest);
        assertThat(item.getProjectCode()).isNull();
        item = demandPlanItemService.approve(item.getId(), "cycle project approved");

        DemandPlanningCycleResponse totalsAfterItem = demandPlanningCycleService.get(cycle.getId());
        assertThat(totalsAfterItem.getTotalPlanCount()).isEqualTo(2);
        assertThat(totalsAfterItem.getTotalItemCount()).isEqualTo(1);
        assertThat(totalsAfterItem.getTotalPlannedAmount()).isEqualByComparingTo("2400");

        assertThat(demandPlanService.submit(maintenancePlan.getId()).getStatus()).isEqualTo(DemandPlanStatus.SUBMITTED.name());
        approveAll(DemandPlanApprovalAdapter.MODULE_KEY, maintenancePlan.getId());
        assertThat(demandPlanService.get(maintenancePlan.getId()).getStatus()).isEqualTo(DemandPlanStatus.APPROVED.name());

        assertThat(demandPlanningCycleService.closeCollection(cycle.getId()).getStatus()).isEqualTo(DemandPlanningCycleStatus.COLLECTION_CLOSED.name());
        assertThat(demandPlanningCycleService.submitBudget(cycle.getId(), null).getStatus()).isEqualTo(DemandPlanningCycleStatus.BUDGET_REVIEW.name());
        approveAll(DemandPlanningCycleApprovalAdapter.MODULE_KEY, cycle.getId());
        assertThat(demandPlanningCycleService.get(cycle.getId()).getStatus()).isEqualTo(DemandPlanningCycleStatus.BUDGET_APPROVED.name());

        var reservation = materialReservationService.reserveForDemandItem(
            item.getId(),
            new DemandReservationRequest(warehouse.getId(), null, null, BigDecimal.valueOf(4))
        );
        assertThat(reservation.getProjectCode()).isNull();
        assertThat(demandPlanningCycleService.get(cycle.getId()).getTotalReservedQuantity()).isEqualByComparingTo("4");
    }

    @Test
    void submittingDemandPlanCascadesDraftItemsToSubmitted() {
        Material material = material("MAT-DEMAND-CASCADE-1");

        DemandPlanCreateRequest planRequest = new DemandPlanCreateRequest();
        planRequest.setCode("DP-CASCADE-" + UUID.randomUUID().toString().substring(0, 8));
        planRequest.setName("План с черновыми позициями");
        planRequest.setPlanYear(Year.now().getValue());
        planRequest.setPeriodType(DemandPeriodType.YEAR.name());
        planRequest.setDepartmentId(department("DEP-CASCADE-" + UUID.randomUUID().toString().substring(0, 8)).getId());
        planRequest.setOwnerUsername("planner");
        planRequest.setCurrencyCode("UZS");
        DemandPlanResponse plan = demandPlanService.create(planRequest);

        // Позиция остаётся в DRAFT — её отдельно не отправляем.
        DemandPlanItemResponse draftItem = demandPlanItemService.create(
            demandItem(plan, material, DemandType.PROJECT.name(), DemandPriority.NORMAL.name(), null, 10, 100)
        );
        assertThat(demandPlanItemRepository.findAnyById(draftItem.getId()).orElseThrow().getStatus())
            .isEqualTo(DemandPlanItemStatus.DRAFT.name());

        // Отправка плана должна пройти без ручной отправки позиции и каскадно
        // перевести черновую позицию в SUBMITTED.
        assertThat(demandPlanService.submit(plan.getId()).getStatus()).isEqualTo(DemandPlanStatus.SUBMITTED.name());
        assertThat(demandPlanItemRepository.findAnyById(draftItem.getId()).orElseThrow().getStatus())
            .isEqualTo(DemandPlanItemStatus.SUBMITTED.name());

        approveAll(DemandPlanApprovalAdapter.MODULE_KEY, plan.getId());
        assertThat(demandPlanService.get(plan.getId()).getStatus()).isEqualTo(DemandPlanStatus.APPROVED.name());
    }

    @Test
    void stockCheckApprovalStepShowsWarehouseAvailabilityAndAdjustsApprovedDemandQuantity() {
        Material material = material("MAT-DEMAND-STOCK-" + UUID.randomUUID().toString().substring(0, 8));
        Warehouse warehouse = warehouse("WH-DEMAND-STOCK-" + UUID.randomUUID().toString().substring(0, 8));
        balance(material, warehouse, BigDecimal.valueOf(12));
        createDemandPlanStockCheckFlow(warehouse.getId());

        DemandPlanCreateRequest planRequest = new DemandPlanCreateRequest();
        planRequest.setCode("DP-STOCK-" + UUID.randomUUID().toString().substring(0, 8));
        planRequest.setName("Stock checked demand plan");
        planRequest.setPlanYear(Year.now().getValue());
        planRequest.setPeriodType(DemandPeriodType.YEAR.name());
        planRequest.setDepartmentId(department("DEP-STOCK-" + UUID.randomUUID().toString().substring(0, 8)).getId());
        planRequest.setOwnerUsername("planner");
        planRequest.setCurrencyCode("UZS");
        DemandPlanResponse plan = demandPlanService.create(planRequest);

        DemandPlanItemResponse demand = demandPlanItemService.create(demandItem(
            plan,
            material,
            DemandType.PROJECT.name(),
            DemandPriority.HIGH.name(),
            null,
            30,
            100
        ));

        assertThat(demandPlanService.submit(plan.getId()).getStatus()).isEqualTo(DemandPlanStatus.SUBMITTED.name());
        var stockTask = approvalEngineService.myTasks("PENDING").stream()
            .filter(task -> plan.getId().equals(task.entityId()))
            .findFirst()
            .orElseThrow();

        assertThat(stockTask.stepType()).isEqualTo(ApprovalStepType.DEMAND_STOCK_CHECK);
        assertThat(stockTask.commentRequired()).isTrue();
        assertThat(stockTask.demandStockReview().warehouseId()).isEqualTo(warehouse.getId());
        assertThat(stockTask.demandStockReview().lines()).singleElement().satisfies(line -> {
            assertThat(line.demandPlanItemId()).isEqualTo(demand.getId());
            assertThat(line.requestedQuantity()).isEqualByComparingTo("30");
            assertThat(line.availableStock()).isEqualByComparingTo("12");
            assertThat(line.differenceQuantity()).isEqualByComparingTo("18");
            assertThat(line.approvedQuantity()).isEqualByComparingTo("30");
            assertThat(line.recommendedApprovedQuantity()).isEqualByComparingTo("18");
            assertThat(line).extracting("unitOfMeasureShortName").isEqualTo("pcs");
        });

        var afterStockCheck = approvalEngineService.approveTask(
            stockTask.id(),
            new ApprovalTaskDecisionRequest(
                "Warehouse stock checked before approval",
                null,
                List.of(new DemandStockReviewLineDecision(demand.getId(), BigDecimal.valueOf(18), "12 units already available in warehouse"))
            )
        );

        DemandPlanItem adjusted = demandPlanItemRepository.findAnyById(demand.getId()).orElseThrow();
        assertThat(adjusted.getApprovedQuantity()).isEqualByComparingTo("18");
        assertThat(adjusted.getEstimatedAmount()).isEqualByComparingTo("1800");
        assertThat(auditLogRepository.existsByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndActionAndUsernameAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            "DemandPlanItem",
            demand.getId(),
            "STOCK_REVIEW_ADJUST",
            "planner"
        )).isTrue();

        var finalTask = afterStockCheck.tasks().stream()
            .filter(task -> task.taskStatus() == ApprovalTaskStatus.PENDING)
            .findFirst()
            .orElseThrow();
        approvalEngineService.approveTask(finalTask.id(), "final approval");
        var prResult = demandPlanService.createPurchaseRequests(plan.getId());

        var prItems = purchaseRequestItemRepository.findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(TenantContext.currentTenant(), prResult.purchaseRequestIds().getFirst());
        assertThat(prItems).singleElement().satisfies(item -> {
            assertThat(item.getMaterialId()).isEqualTo(material.getId());
            assertThat(item.getQuantity()).isEqualByComparingTo("18");
            assertThat(item.getEstimatedPrice()).isEqualByComparingTo("100");
        });
    }

    @Test
    void annualPlanningCreatesPurchaseRequestsDirectlyFromApprovedAndReservedDemand() {
        Material material = material("MAT-DEMAND-1");
        Warehouse warehouse = warehouse("WH-DEMAND-1");
        StockBalance balance = balance(material, warehouse, BigDecimal.valueOf(100));

        DemandPlanCreateRequest planRequest = new DemandPlanCreateRequest();
        planRequest.setCode("DP-" + Year.now().getValue());
        planRequest.setName("Годовой план потребности");
        planRequest.setPlanYear(Year.now().getValue());
        planRequest.setPeriodType(DemandPeriodType.YEAR.name());
        planRequest.setDepartmentId(department("DEP-ANNUAL-" + UUID.randomUUID().toString().substring(0, 8)).getId());
        planRequest.setOwnerUsername("planner");
        planRequest.setCurrencyCode("UZS");
        DemandPlanResponse plan = demandPlanService.create(planRequest);

        DemandPlanItemCreateRequest projectDemandRequest = demandItem(plan, material, DemandType.PROJECT.name(), DemandPriority.HIGH.name(), null, 10, 100);
        DemandPlanItemResponse projectDemand = demandPlanItemService.create(projectDemandRequest);
        projectDemand = demandPlanItemService.approve(projectDemand.getId(), "project approved");

        DemandPlanItemCreateRequest repairDemandRequest = demandItem(plan, material, DemandType.REPAIR.name(), DemandPriority.CRITICAL.name(), "RPR-009", 20, 100);
        DemandPlanItemResponse repairDemand = demandPlanItemService.create(repairDemandRequest);
        repairDemand = demandPlanItemService.approve(repairDemand.getId(), "repair approved");

        var reservation = materialReservationService.reserveForDemandItem(
            projectDemand.getId(),
            new DemandReservationRequest(warehouse.getId(), null, null, BigDecimal.valueOf(5))
        );
        assertThat(reservation.getStatus()).isEqualTo(MaterialReservationStatus.ACTIVE.name());

        balance = stockBalanceRepository.findAnyById(balance.getId()).orElseThrow();
        assertThat(balance.getAvailableQuantity()).isEqualByComparingTo("95");
        assertThat(balance.getReservedQuantity()).isEqualByComparingTo("5");
        assertThat(demandPlanItemRepository.findAnyById(projectDemand.getId()).orElseThrow().getStatus()).isEqualTo(DemandPlanItemStatus.RESERVED.name());

        assertThat(demandPlanService.submit(plan.getId()).getStatus()).isEqualTo(DemandPlanStatus.SUBMITTED.name());
        approveAll(DemandPlanApprovalAdapter.MODULE_KEY, plan.getId());
        assertThat(demandPlanService.get(plan.getId()).getStatus()).isEqualTo(DemandPlanStatus.APPROVED.name());

        assertThat(consolidatedDemandRepository.findByTenantIdAndDemandPlan_IdAndIsDeletedFalse(TenantContext.currentTenant(), plan.getId())).isEmpty();

        var prResult = demandPlanService.createPurchaseRequests(plan.getId());
        assertThat(prResult.purchaseRequestCount()).isEqualTo(1);
        assertThat(consolidatedDemandRepository.findByTenantIdAndDemandPlan_IdAndIsDeletedFalse(TenantContext.currentTenant(), plan.getId())).isEmpty();
        var prItems = purchaseRequestItemRepository.findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(TenantContext.currentTenant(), prResult.purchaseRequestIds().getFirst());
        assertThat(prItems).singleElement().satisfies(item -> {
            assertThat(item.getMaterialId()).isEqualTo(material.getId());
            assertThat(item.getQuantity()).isEqualByComparingTo("30");
            assertThat(item.getEstimatedPrice()).isEqualByComparingTo("100");
        });
        assertThat(demandPlanItemRepository.findAnyById(projectDemand.getId()).orElseThrow().getStatus()).isEqualTo(DemandPlanItemStatus.CONVERTED_TO_PR.name());

        var released = materialReservationService.release(
            reservation.getId(),
            new ReleaseReservationRequest(BigDecimal.valueOf(2), "partial release", reservation.getVersion()),
            "demand-management-release-" + reservation.getId()
        );
        assertThat(released.getStatus()).isEqualTo(MaterialReservationStatus.PARTIALLY_RELEASED.name());
        balance = stockBalanceRepository.findAnyById(balance.getId()).orElseThrow();
        assertThat(balance.getAvailableQuantity()).isEqualByComparingTo("97");
        assertThat(balance.getReservedQuantity()).isEqualByComparingTo("3");
        assertThat(demandPlanItemRepository.findAnyById(projectDemand.getId()).orElseThrow().getStatus()).isEqualTo(DemandPlanItemStatus.CONVERTED_TO_PR.name());
    }

    private DemandPlanItemCreateRequest demandItem(
        DemandPlanResponse plan,
        Material material,
        String demandType,
        String priority,
        String repairOrderCode,
        int quantity,
        int unitPrice
    ) {
        DemandPlanItemCreateRequest item = new DemandPlanItemCreateRequest();
        item.setDemandPlanId(plan.getId());
        item.setName(demandType + " " + material.getCode());
        item.setMaterialId(material.getId());
        item.setDemandType(demandType);
        item.setPriority(priority);
        item.setRepairOrderCode(repairOrderCode);
        item.setRequestedQuantity(BigDecimal.valueOf(quantity));
        item.setApprovedQuantity(BigDecimal.valueOf(quantity));
        item.setEstimatedUnitPrice(BigDecimal.valueOf(unitPrice));
        item.setRequiredDate(LocalDate.now().plusDays(60));
        item.setCurrencyCode("UZS");
        return item;
    }

    private Material material(String code) {
        Material material = new Material();
        material.setCode(code);
        material.setName(code);
        material.setStatus("ACTIVE");
        UnitOfMeasure unit = new UnitOfMeasure();
        unit.setCode("UOM-" + code);
        unit.setName("Pieces");
        unit.setStatus("ACTIVE");
        unit.setMeasurementNameShort("pcs");
        unit.setMeasurementNameLong("Pieces");
        unit = unitOfMeasureRepository.save(unit);
        material.setUnitOfMeasureId(unit.getId());
        return materialRepository.save(material);
    }

    private Warehouse warehouse(String code) {
        Warehouse warehouse = new Warehouse();
        warehouse.setCode(code);
        warehouse.setName(code);
        warehouse.setStatus("ACTIVE");
        return warehouseRepository.save(warehouse);
    }

    private StockBalance balance(Material material, Warehouse warehouse, BigDecimal quantity) {
        StockBalance balance = new StockBalance();
        balance.setCode("BAL-" + material.getCode());
        balance.setName("Demand test balance");
        balance.setStatus("ACTIVE");
        balance.setMaterialId(material.getId());
        balance.setWarehouseId(warehouse.getId());
        balance.setQuantity(quantity);
        balance.setAvailableQuantity(quantity);
        balance.setReservedQuantity(BigDecimal.ZERO);
        balance.setTotalValue(quantity.multiply(BigDecimal.valueOf(100)));
        return stockBalanceRepository.save(balance);
    }

    private Department department(String code) {
        Department department = new Department();
        department.setCode(code);
        department.setName("Business unit " + code.substring(Math.max(0, code.length() - 8)));
        department.setStatus("ACTIVE");
        department.setDepartmentType("MAINTENANCE");
        return departmentRepository.save(department);
    }

    private BudgetItem budgetItem(String code) {
        return budgetItem(code, BigDecimal.valueOf(100_000));
    }

    private BudgetItem budgetItem(String code, BigDecimal annualLimit) {
        BudgetItem budgetItem = new BudgetItem();
        budgetItem.setCode(code);
        budgetItem.setName(code);
        budgetItem.setStatus("ACTIVE");
        budgetItem.setAnnualLimit(annualLimit);
        budgetItem.setReservedAmount(BigDecimal.ZERO);
        budgetItem.setUsedAmount(BigDecimal.ZERO);
        return budgetItemRepository.save(budgetItem);
    }

    private void approveAll(String moduleKey, UUID entityId) {
        var instance = approvalEngineService.currentInstance(moduleKey, entityId);
        while (instance.approvalStatus() != ApprovalInstanceStatus.APPROVED) {
            var task = instance.tasks().stream()
                .filter(candidate -> candidate.taskStatus() == ApprovalTaskStatus.PENDING)
                .findFirst()
                .orElseThrow();
            instance = approvalEngineService.approveTask(task.id(), "ok");
        }
    }

    private void ensureApprovalFlow(String moduleKey, String name, String stepCodePrefix, String approverRole, RequestType requestType) {
        ApprovalFlow saved = approvalFlowRepository.findFirstByTenantIdAndCompanyIdAndModuleKeyAndDepartmentIdIsNullAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            moduleKey
        ).orElseGet(() -> {
            ApprovalFlow flow = new ApprovalFlow();
            flow.setCode("TEST-FLOW-" + UUID.randomUUID().toString().substring(0, 8));
            flow.setName(name);
            flow.setStatus("ACTIVE");
            flow.setModuleKey(moduleKey);
            flow.setActive(true);
            return approvalFlowRepository.save(flow);
        });
        saved.setRequestType(requestType == null ? null : ApprovalRequestType.valueOf(requestType.name()));
        approvalFlowRepository.save(saved);
        List<ApprovalFlowStep> steps = approvalFlowStepRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(),
            saved.getId()
        );
        if (steps.isEmpty()) {
            ApprovalFlowStep created = new ApprovalFlowStep();
            created.setApprovalFlowId(saved.getId());
            created.setCode(stepCodePrefix + "-" + UUID.randomUUID().toString().substring(0, 8));
            created.setName(name + " step");
            created.setStepOrder(1);
            steps = List.of(created);
        }
        steps.forEach(step -> {
            step.setStatus("ACTIVE");
            step.setAssigneeType(ApprovalAssigneeType.ROLE);
            step.setAssigneeRole(approverRole);
            step.setAssigneeUserId(null);
            step.setAssigneeUsername(null);
            step.setCanReturn(true);
            approvalFlowStepRepository.save(step);
        });
    }

    private void createDemandPlanStockCheckFlow(UUID warehouseId) {
        ApprovalFlow flow = new ApprovalFlow();
        flow.setCode("TEST-DP-STOCK-" + UUID.randomUUID().toString().substring(0, 8));
        flow.setName("Demand plan stock check flow");
        flow.setStatus("ACTIVE");
        flow.setModuleKey(DemandPlanApprovalAdapter.MODULE_KEY);
        flow.setRequestType(ApprovalRequestType.GOODS);
        flow.setActive(true);
        ApprovalFlow saved = approvalFlowRepository.save(flow);

        ApprovalFlowStep stockStep = new ApprovalFlowStep();
        stockStep.setApprovalFlowId(saved.getId());
        stockStep.setCode("TEST-DP-STOCK-STEP-" + UUID.randomUUID().toString().substring(0, 8));
        stockStep.setName("Warehouse stock check");
        stockStep.setStepOrder(1);
        stockStep.setStepType(ApprovalStepType.DEMAND_STOCK_CHECK);
        stockStep.setWarehouseId(warehouseId);
        stockStep.setAssigneeType(ApprovalAssigneeType.ROLE);
        stockStep.setAssigneeRole("ADMIN");
        stockStep.setCanReturn(true);
        stockStep.setCommentRequired(true);
        approvalFlowStepRepository.save(stockStep);

        ApprovalFlowStep approvalStep = new ApprovalFlowStep();
        approvalStep.setApprovalFlowId(saved.getId());
        approvalStep.setCode("TEST-DP-FINAL-STEP-" + UUID.randomUUID().toString().substring(0, 8));
        approvalStep.setName("Final approval");
        approvalStep.setStepOrder(2);
        approvalStep.setStepType(ApprovalStepType.STANDARD);
        approvalStep.setAssigneeType(ApprovalAssigneeType.ROLE);
        approvalStep.setAssigneeRole("ADMIN");
        approvalStep.setCanReturn(true);
        approvalFlowStepRepository.save(approvalStep);
    }
}
