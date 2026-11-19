package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.entity.ApprovalFlow;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.entity.DemandPlanItemMonth;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.enums.DemandPeriodType;
import com.enterprise.mm.enums.DemandPlanStatus;
import com.enterprise.mm.enums.DemandPriority;
import com.enterprise.mm.enums.DemandType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.ApprovalRequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.DemandPlanCreateRequest;
import com.enterprise.mm.payload.DemandPlanItemCreateRequest;
import com.enterprise.mm.payload.DemandPlanItemMonthDto;
import com.enterprise.mm.payload.DemandPlanItemResponse;
import com.enterprise.mm.payload.DemandPlanResponse;
import com.enterprise.mm.repository.ApprovalFlowRepository;
import com.enterprise.mm.repository.ApprovalFlowStepRepository;
import com.enterprise.mm.repository.DemandPlanItemMonthRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import com.enterprise.mm.service.DemandPlanItemService;
import com.enterprise.mm.service.DemandPlanService;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.DemandPlanApprovalAdapter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;
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
class MonthlyDemandPlanningTest {
    @Autowired private DemandPlanService demandPlanService;
    @Autowired private DemandPlanItemService demandPlanItemService;
    @Autowired private DemandPlanItemMonthRepository monthRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private UnitOfMeasureRepository unitOfMeasureRepository;
    @Autowired private PurchaseRequestRepository purchaseRequestRepository;
    @Autowired private ApprovalFlowRepository approvalFlowRepository;
    @Autowired private ApprovalFlowStepRepository approvalFlowStepRepository;
    @Autowired private ApprovalEngineService approvalEngineService;

    @BeforeEach
    void setupApprovalFlow() {
        ensureApprovalFlow(DemandPlanApprovalAdapter.MODULE_KEY, "Monthly DP flow", "MDP-STEP", "ADMIN", RequestType.GOODS);
    }

    @Test
    void createDemandItemWithMonthsPersistsMonthRowsAndCalculatesTotal() {
        Material mat = createMaterial("MAT-MONTHLY-1");
        DemandPlanResponse plan = createPlan("DP-MONTHLY-1");

        DemandPlanItemCreateRequest req = new DemandPlanItemCreateRequest();
        req.setDemandPlanId(plan.getId());
        req.setMaterialId(mat.getId());
        req.setDemandType(DemandType.OPERATIONS.name());
        req.setPriority(DemandPriority.NORMAL.name());
        req.setRequestedQuantity(BigDecimal.valueOf(60));
        req.setEstimatedUnitPrice(BigDecimal.valueOf(100));
        req.setMonths(List.of(
            new DemandPlanItemMonthDto(1, BigDecimal.valueOf(10), BigDecimal.ZERO, BigDecimal.ZERO),
            new DemandPlanItemMonthDto(2, BigDecimal.valueOf(20), BigDecimal.ZERO, BigDecimal.ZERO),
            new DemandPlanItemMonthDto(3, BigDecimal.valueOf(30), BigDecimal.ZERO, BigDecimal.ZERO)
        ));

        DemandPlanItemResponse res = demandPlanItemService.create(req);

        assertThat(res.getRequestedQuantity()).isEqualByComparingTo("60");
        assertThat(res.getMonths()).hasSize(12);
        assertThat(res.getRequiredDate()).isEqualTo(LocalDate.of(plan.getPlanYear(), 1, 1));

        List<DemandPlanItemMonth> storedMonths = monthRepository.findByDemandPlanItemIdAndIsDeletedFalseOrderByMonthAsc(res.getId());
        assertThat(storedMonths).hasSize(12);
        assertThat(storedMonths.get(0).getQty()).isEqualByComparingTo("10");
        assertThat(storedMonths.get(1).getQty()).isEqualByComparingTo("20");
        assertThat(storedMonths.get(2).getQty()).isEqualByComparingTo("30");
    }

    @Test
    void createDemandItemWithoutMonthsAutoSplitsAcross12Months() {
        Material mat = createMaterial("MAT-MONTHLY-2");
        DemandPlanResponse plan = createPlan("DP-MONTHLY-2");

        DemandPlanItemCreateRequest req = new DemandPlanItemCreateRequest();
        req.setDemandPlanId(plan.getId());
        req.setMaterialId(mat.getId());
        req.setDemandType(DemandType.OPERATIONS.name());
        req.setPriority(DemandPriority.NORMAL.name());
        req.setRequestedQuantity(BigDecimal.valueOf(120));
        req.setEstimatedUnitPrice(BigDecimal.valueOf(10));

        DemandPlanItemResponse res = demandPlanItemService.create(req);

        assertThat(res.getRequestedQuantity()).isEqualByComparingTo("120");
        assertThat(res.getMonths()).hasSize(12);

        List<DemandPlanItemMonth> storedMonths = monthRepository.findByDemandPlanItemIdAndIsDeletedFalseOrderByMonthAsc(res.getId());
        assertThat(storedMonths).hasSize(12);
        for (DemandPlanItemMonth m : storedMonths) {
            assertThat(m.getQty()).isEqualByComparingTo("10");
        }
    }

    @Test
    void derivedRequiredDateUsesEarliestMonthWithQuantity() {
        Material mat = createMaterial("MAT-MONTHLY-3");
        DemandPlanResponse plan = createPlan("DP-MONTHLY-3");

        DemandPlanItemCreateRequest req = new DemandPlanItemCreateRequest();
        req.setDemandPlanId(plan.getId());
        req.setMaterialId(mat.getId());
        req.setDemandType(DemandType.OPERATIONS.name());
        req.setPriority(DemandPriority.NORMAL.name());
        req.setRequestedQuantity(BigDecimal.valueOf(50));
        req.setEstimatedUnitPrice(BigDecimal.valueOf(50));
        req.setMonths(List.of(
            new DemandPlanItemMonthDto(1, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
            new DemandPlanItemMonthDto(2, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
            new DemandPlanItemMonthDto(4, BigDecimal.valueOf(50), BigDecimal.ZERO, BigDecimal.ZERO)
        ));

        DemandPlanItemResponse res = demandPlanItemService.create(req);

        assertThat(res.getRequiredDate()).isEqualTo(LocalDate.of(plan.getPlanYear(), 4, 1));
    }

    @Test
    void invalidMonthOrNegativeQuantityRejects() {
        Material mat = createMaterial("MAT-MONTHLY-4");
        DemandPlanResponse plan = createPlan("DP-MONTHLY-4");

        DemandPlanItemCreateRequest invalidMonthReq = new DemandPlanItemCreateRequest();
        invalidMonthReq.setDemandPlanId(plan.getId());
        invalidMonthReq.setMaterialId(mat.getId());
        invalidMonthReq.setDemandType(DemandType.OPERATIONS.name());
        invalidMonthReq.setPriority(DemandPriority.NORMAL.name());
        invalidMonthReq.setRequestedQuantity(BigDecimal.TEN);
        invalidMonthReq.setMonths(List.of(new DemandPlanItemMonthDto(13, BigDecimal.valueOf(10), BigDecimal.ZERO, BigDecimal.ZERO)));

        assertThatThrownBy(() -> demandPlanItemService.create(invalidMonthReq))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Month must be between 1 and 12");

        DemandPlanItemCreateRequest negativeQtyReq = new DemandPlanItemCreateRequest();
        negativeQtyReq.setDemandPlanId(plan.getId());
        negativeQtyReq.setMaterialId(mat.getId());
        negativeQtyReq.setDemandType(DemandType.OPERATIONS.name());
        negativeQtyReq.setPriority(DemandPriority.NORMAL.name());
        negativeQtyReq.setRequestedQuantity(BigDecimal.ONE);
        negativeQtyReq.setMonths(List.of(new DemandPlanItemMonthDto(1, BigDecimal.valueOf(-5), BigDecimal.ZERO, BigDecimal.ZERO)));

        assertThatThrownBy(() -> demandPlanItemService.create(negativeQtyReq))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Monthly quantity cannot be negative");
    }

    @Test
    void downstreamPurchaseRequestUsesDerivedRequiredDateFromMonthlyDemand() {
        Material mat = createMaterial("MAT-MONTHLY-5");
        DemandPlanResponse plan = createPlan("DP-MONTHLY-5");

        DemandPlanItemCreateRequest req = new DemandPlanItemCreateRequest();
        req.setDemandPlanId(plan.getId());
        req.setMaterialId(mat.getId());
        req.setDemandType(DemandType.OPERATIONS.name());
        req.setPriority(DemandPriority.NORMAL.name());
        req.setRequestedQuantity(BigDecimal.valueOf(40));
        req.setEstimatedUnitPrice(BigDecimal.valueOf(100));
        req.setMonths(List.of(
            new DemandPlanItemMonthDto(5, BigDecimal.valueOf(40), BigDecimal.ZERO, BigDecimal.ZERO)
        ));

        DemandPlanItemResponse itemRes = demandPlanItemService.create(req);
        demandPlanItemService.approve(itemRes.getId(), "approved");

        demandPlanService.submit(plan.getId());
        approveAll(DemandPlanApprovalAdapter.MODULE_KEY, plan.getId());

        var prResult = demandPlanService.createPurchaseRequests(plan.getId());
        assertThat(prResult.purchaseRequestCount()).isEqualTo(1);

        PurchaseRequest pr = purchaseRequestRepository.findByIdAndIsDeletedFalse(prResult.purchaseRequestIds().getFirst()).orElseThrow();
        assertThat(pr.getRequiredDate()).isEqualTo(LocalDate.of(plan.getPlanYear(), 5, 1));
    }

    private DemandPlanResponse createPlan(String code) {
        DemandPlanCreateRequest planRequest = new DemandPlanCreateRequest();
        planRequest.setCode(code + "-" + UUID.randomUUID().toString().substring(0, 8));
        planRequest.setName("Monthly test plan " + code);
        planRequest.setPlanYear(Year.now().getValue());
        planRequest.setPeriodType(DemandPeriodType.YEAR.name());
        Department department = new Department();
        department.setCode("MONTHLY-DEPT-" + UUID.randomUUID().toString().substring(0, 8));
        department.setName("Monthly demand planning department");
        department.setStatus("ACTIVE");
        department.setDepartmentType("DEPARTMENT");
        planRequest.setDepartmentId(departmentRepository.save(department).getId());
        planRequest.setOwnerUsername("planner");
        planRequest.setCurrencyCode("UZS");
        return demandPlanService.create(planRequest);
    }

    private Material createMaterial(String code) {
        Material material = new Material();
        material.setCode(code + "-" + UUID.randomUUID().toString().substring(0, 8));
        material.setName("Monthly Material " + code);
        material.setStatus("ACTIVE");
        UnitOfMeasure unit = new UnitOfMeasure();
        unit.setCode("UOM-" + UUID.randomUUID().toString().substring(0, 8));
        unit.setName("Pcs");
        unit.setStatus("ACTIVE");
        unit.setMeasurementNameShort("pcs");
        unit.setMeasurementNameLong("Pieces");
        unit = unitOfMeasureRepository.save(unit);
        material.setUnitOfMeasureId(unit.getId());
        return materialRepository.save(material);
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
            com.enterprise.mm.common.TenantContext.currentTenant(),
            com.enterprise.mm.common.CompanyContext.currentCompany(),
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
            com.enterprise.mm.common.TenantContext.currentTenant(),
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
            step.setCanReturn(true);
            approvalFlowStepRepository.save(step);
        });
    }
}
