package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.DemandPeriodType;
import com.enterprise.mm.enums.DemandPlanningCycleStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.ApprovalRequestType;
import com.enterprise.mm.payload.PurchaseRequestCreateRequest;
import com.enterprise.mm.payload.PurchaseRequestResponse;
import com.enterprise.mm.payload.SegregationOfDutyRuleCreateRequest;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.PurchaseRequestService;
import com.enterprise.mm.service.SegregationOfDutyRuleService;
import com.enterprise.mm.service.approval.PurchaseRequestApprovalAdapter;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.entity.ApprovalFlow;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.DemandPlanningCycle;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.repository.ApprovalFlowRepository;
import com.enterprise.mm.repository.ApprovalFlowStepRepository;
import com.enterprise.mm.repository.DemandPlanRepository;
import com.enterprise.mm.repository.DemandPlanningCycleRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.SegregationOfDutyViolationRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "buyer", roles = "PROCUREMENT_MANAGER")
class SegregationOfDutyTest {
    @Autowired PurchaseRequestService purchaseRequestService;
    @Autowired SegregationOfDutyRuleService segregationOfDutyRuleService;
    @Autowired AuditService auditService;
    @Autowired PurchaseRequestItemRepository purchaseRequestItemRepository;
    @Autowired PurchaseRequestRepository purchaseRequestRepository;
    @Autowired MaterialRepository materialRepository;
    @Autowired SegregationOfDutyViolationRepository violationRepository;
    @Autowired ApprovalFlowRepository approvalFlowRepository;
    @Autowired ApprovalFlowStepRepository approvalFlowStepRepository;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired DemandPlanningCycleRepository demandPlanningCycleRepository;
    @Autowired DemandPlanRepository demandPlanRepository;

    private Department buyerDepartment;

    @BeforeEach
    void setUpDepartmentScope() {
        buyerDepartment = departmentRepository.findAll().stream()
            .filter(department -> !department.isDeleted() && "SOD-BUYER-DEP".equals(department.getCode()))
            .findFirst()
            .orElseGet(() -> {
                Department department = new Department();
                department.setCode("SOD-BUYER-DEP");
                department.setName("Buyer department");
                department.setStatus("ACTIVE");
                department.setDepartmentType("OPERATIONS");
                return departmentRepository.save(department);
            });
        employeeRepository.findByTenantIdAndPersonnelNumberAndIsDeletedFalse(
            TenantContext.currentTenant(),
            "buyer"
        ).orElseGet(() -> {
            Employee employee = new Employee();
            employee.setCode("SOD-BUYER-EMP");
            employee.setName("Buyer employee");
            employee.setStatus("ACTIVE");
            employee.setPersonnelNumber("buyer");
            employee.setDepartment(buyerDepartment);
            return employeeRepository.save(employee);
        });
        ensurePurchaseRequestApprovalFlow();
    }

    private void ensurePurchaseRequestApprovalFlow() {
        approvalFlowRepository.findFirstByTenantIdAndCompanyIdAndModuleKeyAndDepartmentIdIsNullAndRequestTypeAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            PurchaseRequestApprovalAdapter.MODULE_KEY,
            ApprovalRequestType.GOODS
        ).orElseGet(() -> {
            ApprovalFlow flow = new ApprovalFlow();
            flow.setCode("TEST-PR-FLOW-" + UUID.randomUUID().toString().substring(0, 8));
            flow.setName("Test PR approval flow");
            flow.setStatus("ACTIVE");
            flow.setModuleKey(PurchaseRequestApprovalAdapter.MODULE_KEY);
            flow.setRequestType(ApprovalRequestType.GOODS);
            flow.setActive(true);
            ApprovalFlow saved = approvalFlowRepository.save(flow);

            ApprovalFlowStep step = new ApprovalFlowStep();
            step.setApprovalFlowId(saved.getId());
            step.setCode("TEST-PR-STEP-" + UUID.randomUUID().toString().substring(0, 8));
            step.setName("Test PR approval step");
            step.setStatus("ACTIVE");
            step.setStepOrder(1);
            step.setAssigneeType(ApprovalAssigneeType.ROLE);
            step.setAssigneeRole("PROCUREMENT_MANAGER");
            step.setCanReturn(true);
            approvalFlowStepRepository.save(step);
            return saved;
        });
    }

    @Test
    void creatorCannotApproveOwnPurchaseRequestWhenMakerCheckerRuleIsActive() {
        segregationOfDutyRuleService.ensureDefaultMakerCheckerRules();
        Material material = material();

        PurchaseRequestCreateRequest createRequest = new PurchaseRequestCreateRequest();
        createRequest.setDepartmentId(buyerDepartment.getId());
        createRequest.setDemandPlanId(approvedDemandPlanId("SOD-OWN"));
        createRequest.setName("Own approval PR");
        createRequest.setRequiredDate(LocalDate.now().plusDays(7));
        createRequest.setTotalAmount(BigDecimal.valueOf(100));
        createRequest.setCurrencyCode("UZS");
        PurchaseRequestResponse pr = purchaseRequestService.create(createRequest);

        PurchaseRequestItem item = new PurchaseRequestItem();
        item.setName("PR item");
        item.setStatus("OPEN");
        item.setPurchaseRequestId(pr.getId());
        item.setMaterialId(material.getId());
        item.setQuantity(BigDecimal.ONE);
        item.setEstimatedPrice(BigDecimal.valueOf(100));
        purchaseRequestItemRepository.save(item);

        pr = purchaseRequestService.submit(pr.getId());
        assertThat(pr.getStatus()).isEqualTo(PurchaseRequestStatus.APPROVAL_PENDING.name());
        UUID prId = pr.getId();
        assertThatThrownBy(() -> purchaseRequestService.approve(prId, "self approve"))
            .isInstanceOf(com.enterprise.mm.error.BusinessException.class)
            .hasMessageContaining("approval task endpoints");
    }

    @Test
    void warnRuleRecordsViolationButDoesNotBlock() {
        PurchaseRequestCreateRequest createRequest = new PurchaseRequestCreateRequest();
        createRequest.setDepartmentId(buyerDepartment.getId());
        createRequest.setName("Warn SoD PR");
        createRequest.setRequiredDate(LocalDate.now().plusDays(7));
        createRequest.setTotalAmount(BigDecimal.valueOf(100));
        createRequest.setCurrencyCode("UZS");
        PurchaseRequestResponse pr = purchaseRequestService.create(createRequest);
        PurchaseRequest entity = purchaseRequestRepository.findAnyById(pr.getId()).orElseThrow();

        SegregationOfDutyRuleCreateRequest rule = new SegregationOfDutyRuleCreateRequest();
        rule.setName("Warn own archive");
        rule.setEntityType("PurchaseRequest");
        rule.setActionCode("ARCHIVE");
        rule.setRuleType("SAME_USER_FIELD");
        rule.setSubjectField("CREATED_BY");
        rule.setSeverity("WARN");
        rule.setActiveFlag(true);
        rule.setAllowAdminOverride(false);
        rule.setMessage("Предупреждение SoD");
        segregationOfDutyRuleService.create(rule);

        var result = segregationOfDutyRuleService.evaluate("PurchaseRequest", "ARCHIVE", entity);

        assertThat(result.allowed()).isTrue();
        assertThat(result.decision()).isEqualTo("WARN");
        UUID warnPrId = pr.getId();
        assertThat(violationRepository.findAll()).anyMatch(violation ->
            warnPrId.equals(violation.getEntityId())
                && "ARCHIVE".equals(violation.getActionCode())
                && "WARNED".equals(violation.getDecision())
        );
    }

    @Test
    void actionActorCannotPerformLaterConflictingAction() {
        PurchaseRequestCreateRequest createRequest = new PurchaseRequestCreateRequest();
        createRequest.setDepartmentId(buyerDepartment.getId());
        createRequest.setName("Audit history SoD PR");
        createRequest.setRequiredDate(LocalDate.now().plusDays(7));
        createRequest.setTotalAmount(BigDecimal.valueOf(100));
        createRequest.setCurrencyCode("UZS");
        PurchaseRequestResponse pr = purchaseRequestService.create(createRequest);
        auditService.log("PurchaseRequest", pr.getId(), "SUBMIT", "manual submit audit");

        SegregationOfDutyRuleCreateRequest rule = new SegregationOfDutyRuleCreateRequest();
        rule.setName("Submitter cannot review");
        rule.setEntityType("PurchaseRequest");
        rule.setActionCode("REVIEW");
        rule.setRuleType("ACTION_ACTOR_CANNOT_PERFORM");
        rule.setSourceActionCode("SUBMIT");
        rule.setSeverity("BLOCK");
        rule.setActiveFlag(true);
        rule.setAllowAdminOverride(false);
        rule.setMessage("Отправивший не может проверять");
        segregationOfDutyRuleService.create(rule);

        PurchaseRequest entity = purchaseRequestRepository.findAnyById(pr.getId()).orElseThrow();
        assertThatThrownBy(() -> segregationOfDutyRuleService.validate("PurchaseRequest", "REVIEW", entity))
            .hasMessageContaining("Отправивший не может проверять");
        UUID reviewPrId = pr.getId();
        assertThat(violationRepository.findAll()).anyMatch(violation ->
            reviewPrId.equals(violation.getEntityId())
                && "REVIEW".equals(violation.getActionCode())
                && "ACTION_ACTOR_CANNOT_PERFORM".equals(violation.getRuleType())
                && "BLOCKED".equals(violation.getDecision())
        );
    }

    private Material material() {
        Material material = new Material();
        material.setCode("SOD-MAT-" + UUID.randomUUID().toString().substring(0, 8));
        material.setName(material.getCode());
        material.setStatus("ACTIVE");
        return materialRepository.save(material);
    }

    private UUID approvedDemandPlanId(String prefix) {
        int year = LocalDate.now().getYear();
        DemandPlanningCycle cycle = new DemandPlanningCycle();
        cycle.setCode(prefix + "-CYCLE-" + UUID.randomUUID().toString().substring(0, 8));
        cycle.setName(prefix + " approved cycle");
        cycle.setStatus(DemandPlanningCycleStatus.BUDGET_APPROVED.name());
        cycle.setPlanYear(year);
        cycle.setPeriodType(DemandPeriodType.YEAR.name());
        cycle.setPeriodStart(LocalDate.of(year, 1, 1));
        cycle.setPeriodEnd(LocalDate.of(year, 12, 31));
        cycle.setCurrencyCode("UZS");
        cycle = demandPlanningCycleRepository.save(cycle);

        DemandPlan plan = new DemandPlan();
        plan.setCode(prefix + "-PLAN-" + UUID.randomUUID().toString().substring(0, 8));
        plan.setName(prefix + " approved plan");
        plan.setStatus("APPROVED");
        plan.setPlanningCycle(cycle);
        plan.setRequestType(RequestType.GOODS);
        plan.setPlanYear(year);
        plan.setPeriodType(DemandPeriodType.YEAR.name());
        plan.setPeriodStart(LocalDate.of(year, 1, 1));
        plan.setPeriodEnd(LocalDate.of(year, 12, 31));
        plan.setCurrencyCode("UZS");
        plan.setTotalPlannedAmount(BigDecimal.ZERO);
        plan.setTotalApprovedAmount(BigDecimal.ZERO);
        return demandPlanRepository.save(plan).getId();
    }
}
