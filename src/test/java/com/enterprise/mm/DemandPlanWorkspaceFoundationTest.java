package com.enterprise.mm;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AuditLog;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.ApprovalFlow;
import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.entity.ApprovalTask;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.DemandPlanItem;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.DemandPlanningCycle;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.GoodsReceipt;
import com.enterprise.mm.entity.GoodsReceiptLine;
import com.enterprise.mm.entity.IssueRequest;
import com.enterprise.mm.entity.IssueRequestLine;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseOrderSourceAllocation;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.entity.WorkCompletionAct;
import com.enterprise.mm.enums.DemandPriority;
import com.enterprise.mm.enums.DemandType;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalFlowType;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.enums.IssueRequestCostObjectType;
import com.enterprise.mm.enums.IssueRequestStatus;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.ApprovalRequestType;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.enums.WorkInspectionResult;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.ApprovalFlowRepository;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.ApprovalTaskRepository;
import com.enterprise.mm.repository.DemandPlanItemRepository;
import com.enterprise.mm.repository.DemandPlanRepository;
import com.enterprise.mm.repository.DemandPlanningCycleRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.GoodsReceiptLineRepository;
import com.enterprise.mm.repository.GoodsReceiptRepository;
import com.enterprise.mm.repository.IssueRequestLineRepository;
import com.enterprise.mm.repository.IssueRequestRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseOrderSourceAllocationRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.repository.WorkCompletionActRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.seed.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemandPlanWorkspaceFoundationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired DemandPlanItemRepository demandPlanItemRepository;
    @Autowired DemandPlanRepository demandPlanRepository;
    @Autowired DemandPlanningCycleRepository demandPlanningCycleRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired MaterialRepository materialRepository;
    @Autowired PurchaseRequestRepository purchaseRequestRepository;
    @Autowired PurchaseRequestItemRepository purchaseRequestItemRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired PurchaseOrderRepository purchaseOrderRepository;
    @Autowired PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Autowired PurchaseOrderSourceAllocationRepository sourceAllocationRepository;
    @Autowired GoodsReceiptRepository goodsReceiptRepository;
    @Autowired GoodsReceiptLineRepository goodsReceiptLineRepository;
    @Autowired ContractRepository contractRepository;
    @Autowired ServiceOrderRepository serviceOrderRepository;
    @Autowired ServiceAcceptanceActRepository serviceAcceptanceActRepository;
    @Autowired WorkCompletionActRepository workCompletionActRepository;
    @Autowired UnitOfMeasureRepository unitOfMeasureRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired UserRepository userRepository;
    @Autowired IssueRequestRepository issueRequestRepository;
    @Autowired IssueRequestLineRepository issueRequestLineRepository;
    @Autowired ApprovalFlowRepository approvalFlowRepository;
    @Autowired ApprovalInstanceRepository approvalInstanceRepository;
    @Autowired ApprovalTaskRepository approvalTaskRepository;

    private String tenantId;
    private Department ownDepartment;
    private Department otherDepartment;

    @BeforeEach
    void setUp() {
        tenantId = "dp-workspace-" + UUID.randomUUID();
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        ownDepartment = department("OWN");
        otherDepartment = department("OTHER");
        employee("department-originator", ownDepartment);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void departmentOriginatorCreatesForAuthenticatedDepartmentWhenPayloadOmitsDepartment() throws Exception {
        mockMvc.perform(post("/api/demand-plans")
                .with(user("department-originator").roles("DEPARTMENT_WORKER"))
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(planBody(null, "Department owned plan"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.departmentId").value(ownDepartment.getId().toString()));
    }

    @Test
    void departmentOriginatorCannotForgeAnotherDepartment() throws Exception {
        mockMvc.perform(post("/api/demand-plans")
                .with(user("department-originator").roles("DEPARTMENT_HEAD"))
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(planBody(otherDepartment.getId(), "Forged department plan"))))
            .andExpect(status().is4xxClientError());
    }

    @Test
    void adminWithDepartmentRoleCanCreateForPayloadDepartmentWithoutAssignment() throws Exception {
        mockMvc.perform(post("/api/demand-plans")
                .with(user("admin-with-department-role").roles("ADMIN", "DEPARTMENT_WORKER"))
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(planBody(ownDepartment.getId(), "Admin delegated department plan"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.departmentId").value(ownDepartment.getId().toString()));
    }

    @Test
    void procurementRoleCannotOriginateDemandPlan() throws Exception {
        mockMvc.perform(post("/api/demand-plans")
                .with(user("buyer").roles("PROCUREMENT_SPECIALIST"))
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(planBody(ownDepartment.getId(), "Procurement originated plan"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void demandPlanDeleteRequiresDraft() throws Exception {
        String planId = createAsAdmin("Non draft delete guard");

        mockMvc.perform(post("/api/demand-plans/{id}/start-collection", planId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk());

        mockMvc.perform(delete("/api/demand-plans/{id}", planId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().is4xxClientError());
    }

    @Test
    void workspaceEndpointsReturnInitialReadModel() throws Exception {
        User owner = appUser("department-originator");
        owner.setFullName("Demand Owner Full Name");
        userRepository.save(owner);
        String planId = createAsAdmin("Workspace visible plan");

        mockMvc.perform(get("/api/demand-plans/workspace")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[*].id", hasItem(planId)))
            .andExpect(jsonPath("$.data.content[0].createdAt").exists())
            .andExpect(jsonPath("$.data.content[0].ownerFullName").value("Demand Owner Full Name"));

        mockMvc.perform(get("/api/demand-plans/{id}/workspace", planId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.header.id").value(planId))
            .andExpect(jsonPath("$.data.header.createdAt").exists())
            .andExpect(jsonPath("$.data.header.ownerFullName").value("Demand Owner Full Name"))
            .andExpect(jsonPath("$.data.stages[?(@.key == 'DEMAND_PLAN')].responsible", hasItem("Demand Owner Full Name")))
            .andExpect(jsonPath("$.data.supplySummary.status").value("NOT_STARTED"));
    }

    @Test
    void workspaceListShowsNestedCreateItemsAndCalculatedAmount() throws Exception {
        Material material = material();
        String planId = createAsAdmin(planBodyWithItem(ownDepartment.getId(), "Workspace amount plan", material));

        mockMvc.perform(get("/api/demand-plans/workspace")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[?(@.id == '" + planId + "')].itemCount", hasItem(1)))
            .andExpect(jsonPath("$.data.content[?(@.id == '" + planId + "')].plannedAmount", hasItem(700000.0)));

        mockMvc.perform(get("/api/demand-plans/{id}/workspace", planId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.header.plannedAmount").value(700000))
            .andExpect(jsonPath("$.data.items[0].requestedQuantity").value(7));
    }

    @Test
    void workspaceSummaryItemsAggregateQuantityAndFilterPlansByMaterial() throws Exception {
        Material target = localizedUnitMaterial();
        Material other = material();
        String firstPlanId = createAsAdmin(planBodyWithItem(ownDepartment.getId(), "Target material first plan", target));
        String secondPlanId = createAsAdmin(planBodyWithItem(ownDepartment.getId(), "Target material second plan", target));
        createAsAdmin(planBodyWithItem(ownDepartment.getId(), "Other material plan", other));

        mockMvc.perform(get("/api/demand-plans/workspace/summary")
                .param("materialId", target.getId().toString())
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId)
                .header("Accept-Language", "uz"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total.count").value(2))
            .andExpect(jsonPath("$.data.rows[0].items[0].materialId").value(target.getId().toString()))
            .andExpect(jsonPath("$.data.rows[0].items[0].materialName").value("Workspace localized unit material"))
            .andExpect(jsonPath("$.data.rows[0].items[0].unit").value("Dona"))
            .andExpect(jsonPath("$.data.rows[0].items[0].requestedQuantity").value(14))
            .andExpect(jsonPath("$.data.rows[0].items[0].planCount").value(2));

        mockMvc.perform(get("/api/demand-plans/workspace")
                .param("materialId", target.getId().toString())
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(2))
            .andExpect(jsonPath("$.data.content[*].id", hasItem(firstPlanId)))
            .andExpect(jsonPath("$.data.content[*].id", hasItem(secondPlanId)));
    }

    @Test
    void workspaceSummaryAggregatesMatchingPlansByStatusAndMaterial() throws Exception {
        Material material = material();
        createAsAdmin(planBodyWithItem(ownDepartment.getId(), "Workspace summary draft one", material));
        createAsAdmin(planBodyWithItem(ownDepartment.getId(), "Workspace summary draft two", material));
        String activePlanId = createAsAdmin(planBodyWithItem(ownDepartment.getId(), "Workspace summary active", material));
        activatePlan(activePlanId);

        mockMvc.perform(get("/api/demand-plans/workspace/summary")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total.count").value(3))
            .andExpect(jsonPath("$.data.total.plannedAmount").value(2100000))
            .andExpect(jsonPath("$.data.total.currencyCode").value("UZS"))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'DRAFT')].count", hasItem(2)))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'DRAFT')].plannedAmount", hasItem(1400000.0)))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'DRAFT')].items[0].itemType", hasItem("MATERIAL")))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'DRAFT')].items[0].materialId", hasItem(material.getId().toString())))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'DRAFT')].items[0].requestedQuantity", hasItem(14)))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'DRAFT')].items[0].plannedAmount", hasItem(1400000.0)))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'DRAFT')].items[0].planCount", hasItem(2)))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'ACTIVE')].count", hasItem(1)));

        mockMvc.perform(get("/api/demand-plans/workspace/summary")
                .param("status", "ACTIVE")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total.count").value(1))
            .andExpect(jsonPath("$.data.rows[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.rows[0].items[0].planCount").value(1));
    }

    @Test
    void workspaceDetailDoesNotExposeDraftQuantityAsApproved() throws Exception {
        String planId = createAsAdmin("Workspace draft quantity plan");
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        Material material = material();
        DemandPlanItem item = new DemandPlanItem();
        item.setCode("DPI-" + suffix());
        item.setName("Draft demand item");
        item.setStatus("DRAFT");
        item.setDemandPlanId(UUID.fromString(planId));
        item.setDepartmentId(ownDepartment.getId());
        item.setMaterialId(material.getId());
        item.setDemandType(DemandType.OPERATIONS.name());
        item.setPriority(DemandPriority.HIGH.name());
        item.setRequestedQuantity(new BigDecimal("5"));
        item.setApprovedQuantity(new BigDecimal("5"));
        item.setReservedQuantity(BigDecimal.ZERO);
        item.setEstimatedUnitPrice(BigDecimal.TEN);
        item.setEstimatedAmount(new BigDecimal("50"));
        item.setCurrencyCode("UZS");
        item.setRequiredDate(LocalDate.now().plusDays(10));
        demandPlanItemRepository.save(item);

        mockMvc.perform(get("/api/demand-plans/{id}/workspace", planId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].requestedQuantity").value(5))
            .andExpect(jsonPath("$.data.items[0].approvedQuantity").value(0));
    }

    @Test
    void activeWorkspaceDoesNotEnablePurchaseRequestCreationForDraftDemandItems() throws Exception {
        String planId = createAsAdmin("Workspace active draft item plan");
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        DemandPlan plan = demandPlanRepository.findByIdAndIsDeletedFalse(UUID.fromString(planId)).orElseThrow();
        plan.setStatus("ACTIVE");
        demandPlanRepository.save(plan);
        Material material = material();
        DemandPlanItem item = new DemandPlanItem();
        item.setCode("DPI-" + suffix());
        item.setName("Draft item with entered approved quantity");
        item.setStatus("DRAFT");
        item.setDemandPlanId(plan.getId());
        item.setDepartmentId(ownDepartment.getId());
        item.setMaterialId(material.getId());
        item.setDemandType(DemandType.OPERATIONS.name());
        item.setPriority(DemandPriority.HIGH.name());
        item.setRequestedQuantity(new BigDecimal("5"));
        item.setApprovedQuantity(new BigDecimal("5"));
        item.setReservedQuantity(BigDecimal.ZERO);
        item.setEstimatedUnitPrice(BigDecimal.TEN);
        item.setEstimatedAmount(new BigDecimal("50"));
        item.setCurrencyCode("UZS");
        item.setRequiredDate(LocalDate.now().plusDays(10));
        demandPlanItemRepository.save(item);

        mockMvc.perform(get("/api/demand-plans/{id}/workspace", planId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.availableActions.primary.key").value("CREATE_PURCHASE_REQUEST"))
            .andExpect(jsonPath("$.data.availableActions.primary.enabled").value(false))
            .andExpect(jsonPath("$.data.availableActions.primary.disabledReasonCode").value("demand_plan.action.no_items_ready_for_purchase_request"));
    }

    @Test
    void workspaceDetailLocalizesItemUnitLongNameFromAcceptLanguage() throws Exception {
        String planId = createAsAdmin("Workspace localized unit plan");
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        Material material = localizedUnitMaterial();
        DemandPlanItem item = new DemandPlanItem();
        item.setCode("DPI-" + suffix());
        item.setName("Localized unit demand item");
        item.setStatus("APPROVED");
        item.setDemandPlanId(UUID.fromString(planId));
        item.setDepartmentId(ownDepartment.getId());
        item.setMaterialId(material.getId());
        item.setDemandType(DemandType.OPERATIONS.name());
        item.setPriority(DemandPriority.HIGH.name());
        item.setRequestedQuantity(new BigDecimal("5"));
        item.setApprovedQuantity(new BigDecimal("5"));
        item.setReservedQuantity(BigDecimal.ZERO);
        item.setEstimatedUnitPrice(BigDecimal.TEN);
        item.setEstimatedAmount(new BigDecimal("50"));
        item.setCurrencyCode("UZS");
        item.setRequiredDate(LocalDate.now().plusDays(10));
        demandPlanItemRepository.save(item);

        mockMvc.perform(get("/api/demand-plans/{id}/workspace", planId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId)
                .header("Accept-Language", "uz"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].unit").value("Dona"));

        mockMvc.perform(get("/api/demand-plans/{id}/workspace", planId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId)
                .header("Accept-Language", "ru"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].unit").value("Штуки"));

        mockMvc.perform(get("/api/demand-plans/{id}/workspace", planId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId)
                .header("Accept-Language", "en-US"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].unit").value("Pieces"));
    }

    @Test
    void collectingWorkspaceDoesNotTreatZeroApprovedDemandAsSupplied() throws Exception {
        String planId = createAsAdmin("Workspace collecting zero approved plan");
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        DemandPlan plan = demandPlanRepository.findByIdAndIsDeletedFalse(UUID.fromString(planId)).orElseThrow();
        plan.setStatus("COLLECTING");
        demandPlanRepository.save(plan);
        Material material = material();
        DemandPlanItem item = new DemandPlanItem();
        item.setCode("DPI-" + suffix());
        item.setName("Zero approved collecting item");
        item.setStatus("DRAFT");
        item.setDemandPlanId(plan.getId());
        item.setDepartmentId(ownDepartment.getId());
        item.setMaterialId(material.getId());
        item.setDemandType(DemandType.OPERATIONS.name());
        item.setPriority(DemandPriority.HIGH.name());
        item.setRequestedQuantity(new BigDecimal("5"));
        item.setApprovedQuantity(BigDecimal.ZERO);
        item.setReservedQuantity(BigDecimal.ZERO);
        item.setEstimatedUnitPrice(BigDecimal.TEN);
        item.setEstimatedAmount(new BigDecimal("50"));
        item.setCurrencyCode("UZS");
        item.setRequiredDate(LocalDate.now().plusDays(10));
        demandPlanItemRepository.save(item);

        mockMvc.perform(get("/api/demand-plans/{id}/workspace", planId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.supplySummary.status").value("NOT_STARTED"))
            .andExpect(jsonPath("$.data.items[0].coverageStatus").value("NOT_STARTED"))
            .andExpect(jsonPath("$.data.stages[?(@.key == 'DEPARTMENT_SUPPLY')].state", hasItem("NOT_STARTED")));
    }

    @Test
    void workspaceDetailIncludesPlanningCycleAsClickableRelatedDocument() throws Exception {
        DemandPlanningCycle cycle = demandPlanningCycle("Annual planning cycle");
        String planId = createAsAdmin("Plan linked to cycle");
        DemandPlan plan = demandPlanRepository.findByIdAndIsDeletedFalse(UUID.fromString(planId)).orElseThrow();
        plan.setPlanningCycle(cycle);
        demandPlanRepository.save(plan);

        mockMvc.perform(get("/api/demand-plans/{id}/workspace", planId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.header.planningCycleName").value("Annual planning cycle"))
            .andExpect(jsonPath("$.data.relatedDocuments[0].documentType").value("DEMAND_PLANNING_CYCLE"))
            .andExpect(jsonPath("$.data.relatedDocuments[0].documentId").value(cycle.getId().toString()))
            .andExpect(jsonPath("$.data.relatedDocuments[0].moduleKey").value("demand-planning-cycles"))
            .andExpect(jsonPath("$.data.relatedDocuments[0].displayName").value("Annual planning cycle"));
    }

    @Test
    void workspaceDetailReturnsApprovalTimelineAndTrackAction() throws Exception {
        String planId = createAsAdmin("Workspace approval plan");
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        DemandPlan plan = demandPlanRepository.findByIdAndIsDeletedFalse(UUID.fromString(planId)).orElseThrow();
        plan.setStatus("SUBMITTED");
        demandPlanRepository.save(plan);
        ApprovalFlow flow = approvalFlow();
        ApprovalInstance instance = approvalInstance(flow, plan.getId());
        User approver = appUser("approver-one");
        approver.setFullName("Approver One Full Name");
        userRepository.save(approver);
        User pendingApprover = appUser("pending-approver");
        pendingApprover.setFullName("Pending Approver Full Name");
        userRepository.save(pendingApprover);
        ApprovalTask approvedTask = approvalTask(instance, 1, ApprovalTaskStatus.APPROVED, null, "approver-one");
        approvedTask.setAssigneeType(ApprovalAssigneeType.USER);
        approvedTask.setAssigneeUsername("approver-one");
        approvalTaskRepository.save(approvedTask);
        ApprovalTask pendingTask = approvalTask(instance, 2, ApprovalTaskStatus.PENDING, null, null);
        pendingTask.setAssigneeType(ApprovalAssigneeType.USER);
        pendingTask.setAssigneeUserId(pendingApprover.getId());
        pendingTask.setAssigneeUsername("pending-approver");
        approvalTaskRepository.save(pendingTask);

        mockMvc.perform(get("/api/demand-plans/{id}/workspace", planId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.approval.moduleKey").value("DEMAND_PLAN"))
            .andExpect(jsonPath("$.data.approval.instanceId").value(instance.getId().toString()))
            .andExpect(jsonPath("$.data.approval.steps[0].assignee").value("Approver One Full Name"))
            .andExpect(jsonPath("$.data.approval.steps[0].actingUser").value("Approver One Full Name"))
            .andExpect(jsonPath("$.data.approval.steps[0].state").value("APPROVED"))
            .andExpect(jsonPath("$.data.approval.steps[1].assignee").value("Pending Approver Full Name"))
            .andExpect(jsonPath("$.data.approval.steps[1].state").value("PENDING"))
            .andExpect(jsonPath("$.data.availableActions.primary.key").value("TRACK_APPROVAL"))
            .andExpect(jsonPath("$.data.availableActions.primary.invocationKind").value("NAVIGATION"))
            .andExpect(jsonPath("$.data.availableActions.primary.targetDocument.documentType").value("APPROVAL_INSTANCE"));
    }

    @Test
    void workspaceDetailMarksPlanApprovalCompletedAsSoonAsApprovalInstanceIsApproved() throws Exception {
        String planId = createAsAdmin("Workspace approved stage plan");
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        DemandPlan plan = demandPlanRepository.findByIdAndIsDeletedFalse(UUID.fromString(planId)).orElseThrow();
        plan.setStatus("SUBMITTED");
        demandPlanRepository.save(plan);
        ApprovalInstance instance = approvalInstance(approvalFlow(), plan.getId());
        instance.setStatus(ApprovalInstanceStatus.APPROVED.name());
        instance.setApprovalStatus(ApprovalInstanceStatus.APPROVED);
        instance.setCompletedAt(LocalDateTime.now());
        approvalInstanceRepository.save(instance);

        mockMvc.perform(get("/api/demand-plans/{id}/workspace", planId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.stages[?(@.key == 'PLAN_APPROVAL')].state", hasItem("COMPLETED")))
            .andExpect(jsonPath("$.data.stages[?(@.key == 'PURCHASE_REQUEST')].current", hasItem(true)));
    }

    @Test
    void workspaceStatusHistoryReturnsActorFullName() throws Exception {
        String planId = createAsAdmin("Workspace status actor plan");
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        User actor = appUser("status-actor");
        actor.setFullName("Status Actor Full Name");
        userRepository.save(actor);
        AuditLog log = new AuditLog();
        log.setCode("AUD-" + suffix());
        log.setName("Workspace status history");
        log.setStatus("ACTIVE");
        log.setEntityType("DemandPlan");
        log.setEntityId(UUID.fromString(planId));
        log.setAction("STATUS_CHANGED");
        log.setUsername("status-actor");
        log.setDetails("Status changed");
        log.setOccurredAt(LocalDateTime.now());
        auditLogRepository.save(log);

        mockMvc.perform(get("/api/demand-plans/{id}/workspace", planId)
            .with(user("admin").roles("ADMIN"))
            .header("X-Tenant-Id", tenantId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.statusHistory[*].actor", hasItem("Status Actor Full Name")));
    }

    @Test
    void workspaceDetailResolvesGoodsCoverageFromAuthoritativeLineage() throws Exception {
        String planId = createAsAdmin("Workspace lineage plan");
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        activatePlan(planId);
        Material material = material();
        PurchaseRequest purchaseRequest = purchaseRequest(planId);
        PurchaseRequestItem purchaseRequestItem = purchaseRequestItem(purchaseRequest, material);
        DemandPlanItem demandItem = demandPlanItem(planId, material, purchaseRequestItem);
        Warehouse warehouse = warehouse();
        User requester = appUser("requester-" + suffix());
        User receiver = appUser("receiver-" + suffix());
        receiver.setFullName("Department Receiver Full Name");
        userRepository.save(receiver);
        Supplier supplier = supplier();
        PurchaseOrder purchaseOrder = purchaseOrder(purchaseRequest, supplier);
        PurchaseOrderItem purchaseOrderItem = purchaseOrderItem(purchaseOrder, material);
        sourceAllocation(purchaseOrderItem, purchaseRequestItem, "10");
        goodsReceipt(purchaseOrder, purchaseOrderItem, supplier, material, warehouse, "6");
        issuedLine(demandItem, material, warehouse, requester, receiver, "4");

        mockMvc.perform(get("/api/demand-plans/{id}/workspace", planId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].orderedQuantity").value(10))
            .andExpect(jsonPath("$.data.items[0].receivedOrAcceptedQuantity").value(6))
            .andExpect(jsonPath("$.data.items[0].departmentIssuedQuantity").value(4))
            .andExpect(jsonPath("$.data.items[0].coverageStatus").value("PARTIAL"))
            .andExpect(jsonPath("$.data.supplySummary.status").value("PARTIAL"))
            .andExpect(jsonPath("$.data.supplySummary.internalActors", hasItem("Department Receiver Full Name")))
            .andExpect(jsonPath("$.data.relatedDocuments[*].documentType", hasItem("PURCHASE_ORDER")))
            .andExpect(jsonPath("$.data.relatedDocuments[*].documentType", hasItem("GOODS_RECEIPT")))
            .andExpect(jsonPath("$.data.relatedDocuments[*].documentType", hasItem("ISSUE_REQUEST")));

        mockMvc.perform(get("/api/demand-plans/workspace")
                .param("view", "PARTIALLY_SUPPLIED")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[*].id", hasItem(planId)));
    }

    @Test
    void workspaceDetailMarksMissingPurchaseOrderAllocationUnavailable() throws Exception {
        String planId = createAsAdmin("Workspace missing allocation plan");
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        activatePlan(planId);
        Material material = material();
        PurchaseRequest purchaseRequest = purchaseRequest(planId);
        PurchaseRequestItem purchaseRequestItem = purchaseRequestItem(purchaseRequest, material);
        demandPlanItem(planId, material, purchaseRequestItem);

        mockMvc.perform(get("/api/demand-plans/{id}/workspace", planId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].coverageStatus").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.data.supplySummary.status").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.data.stages[?(@.key == 'DEPARTMENT_SUPPLY')].state", hasItem("UNSTARTED")))
            .andExpect(jsonPath("$.data.sourceWarnings[0].code").value("demand_plan.workspace.missing_purchase_order_source_allocation"));
    }

    @Test
    void workspaceMarksPurchaseRequestCompletedAndSourcingCurrentAfterPurchaseRequestApproval() throws Exception {
        String planId = createAsAdmin("Workspace sourcing plan");
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        DemandPlan plan = demandPlanRepository.findByIdAndIsDeletedFalse(UUID.fromString(planId)).orElseThrow();
        plan.setStatus("ACTIVE");
        demandPlanRepository.save(plan);
        Material material = material();
        PurchaseRequest purchaseRequest = purchaseRequest(planId);
        PurchaseRequestItem purchaseRequestItem = purchaseRequestItem(purchaseRequest, material);
        demandPlanItem(planId, material, purchaseRequestItem);

        mockMvc.perform(get("/api/demand-plans/{id}/workspace", planId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.stages[?(@.key == 'PURCHASE_REQUEST')].state", hasItem("COMPLETED")))
            .andExpect(jsonPath("$.data.stages[?(@.key == 'SOURCING')].state", hasItem("IN_PROGRESS")))
            .andExpect(jsonPath("$.data.stages[?(@.key == 'SOURCING')].current", hasItem(true)));

        mockMvc.perform(get("/api/demand-plans/workspace")
                .param("processStage", "SOURCING")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[*].id", hasItem(planId)));
    }

    @Test
    void workspaceDetailOpensPurchaseOrderAfterPurchaseOrderExists() throws Exception {
        String planId = createAsAdmin("Workspace purchase order action plan");
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        activatePlan(planId);
        Material material = material();
        PurchaseRequest purchaseRequest = purchaseRequest(planId);
        PurchaseRequestItem purchaseRequestItem = purchaseRequestItem(purchaseRequest, material);
        demandPlanItem(planId, material, purchaseRequestItem);
        Supplier supplier = supplier();
        PurchaseOrder purchaseOrder = purchaseOrder(purchaseRequest, supplier);
        PurchaseOrderItem purchaseOrderItem = purchaseOrderItem(purchaseOrder, material);
        sourceAllocation(purchaseOrderItem, purchaseRequestItem, "10");

        mockMvc.perform(get("/api/demand-plans/{id}/workspace", planId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.availableActions.primary.key").value("OPEN_PURCHASE_ORDER"))
            .andExpect(jsonPath("$.data.availableActions.primary.targetDocument.moduleKey").value("purchase-orders"))
            .andExpect(jsonPath("$.data.availableActions.primary.targetDocument.documentId").value(purchaseOrder.getId().toString()))
            .andExpect(jsonPath("$.data.stages[?(@.key == 'PURCHASE_ORDER')].state", hasItem("COMPLETED")));
    }

    @Test
    void workspaceDetailMarksGoodsSuppliedOnlyAfterDepartmentIssueCoversApprovedQuantity() throws Exception {
        String planId = createAsAdmin("Workspace supplied plan");
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        activatePlan(planId);
        Material material = material();
        PurchaseRequest purchaseRequest = purchaseRequest(planId);
        PurchaseRequestItem purchaseRequestItem = purchaseRequestItem(purchaseRequest, material);
        DemandPlanItem demandItem = demandPlanItem(planId, material, purchaseRequestItem);
        Warehouse warehouse = warehouse();
        User requester = appUser("requester-" + suffix());
        User receiver = appUser("receiver-" + suffix());
        Supplier supplier = supplier();
        PurchaseOrder purchaseOrder = purchaseOrder(purchaseRequest, supplier);
        PurchaseOrderItem purchaseOrderItem = purchaseOrderItem(purchaseOrder, material);
        sourceAllocation(purchaseOrderItem, purchaseRequestItem, "10");
        goodsReceipt(purchaseOrder, purchaseOrderItem, supplier, material, warehouse, "10");
        issuedLine(demandItem, material, warehouse, requester, receiver, "10");

        mockMvc.perform(get("/api/demand-plans/{id}/workspace", planId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].coverageStatus").value("SUPPLIED"))
            .andExpect(jsonPath("$.data.supplySummary.status").value("SUPPLIED"));

        mockMvc.perform(get("/api/demand-plans/workspace")
                .param("view", "SUPPLIED")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[*].id", hasItem(planId)));
    }

    @Test
    void workspaceResolverAttributesSplitPurchaseOrderReceiptsByAllocationShare() throws Exception {
        String planId = createAsAdmin("Workspace split allocation plan");
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        Material material = material();
        PurchaseRequest purchaseRequest = purchaseRequest(planId);
        PurchaseRequestItem firstSource = purchaseRequestItem(purchaseRequest, material);
        firstSource.setQuantity(new BigDecimal("6"));
        purchaseRequestItemRepository.save(firstSource);
        PurchaseRequestItem secondSource = purchaseRequestItem(purchaseRequest, material);
        secondSource.setQuantity(new BigDecimal("4"));
        purchaseRequestItemRepository.save(secondSource);
        DemandPlanItem firstDemand = demandPlanItem(planId, material, firstSource);
        firstDemand.setRequestedQuantity(new BigDecimal("6"));
        firstDemand.setApprovedQuantity(new BigDecimal("6"));
        demandPlanItemRepository.save(firstDemand);
        DemandPlanItem secondDemand = demandPlanItem(planId, material, secondSource);
        secondDemand.setRequestedQuantity(new BigDecimal("4"));
        secondDemand.setApprovedQuantity(new BigDecimal("4"));
        demandPlanItemRepository.save(secondDemand);
        Warehouse warehouse = warehouse();
        Supplier supplier = supplier();
        PurchaseOrder purchaseOrder = purchaseOrder(purchaseRequest, supplier);
        PurchaseOrderItem purchaseOrderItem = purchaseOrderItem(purchaseOrder, material);
        sourceAllocation(purchaseOrderItem, firstSource, "6");
        sourceAllocation(purchaseOrderItem, secondSource, "4");
        goodsReceipt(purchaseOrder, purchaseOrderItem, supplier, material, warehouse, "5");

        mockMvc.perform(get("/api/demand-plans/{id}/workspace", planId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[*].orderedQuantity", org.hamcrest.Matchers.hasItems(6, 4)))
            .andExpect(jsonPath("$.data.items[*].receivedOrAcceptedQuantity", org.hamcrest.Matchers.hasItems(3, 2)));
    }

    @Test
    void workspaceDetailMarksServiceSuppliedAfterApprovedAcceptanceAct() throws Exception {
        String planId = createAsAdmin("Workspace service supplied plan");
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        DemandPlan plan = demandPlanRepository.findByIdAndIsDeletedFalse(UUID.fromString(planId)).orElseThrow();
        plan.setStatus("ACTIVE");
        plan.setRequestType(RequestType.SERVICE);
        demandPlanRepository.save(plan);
        Material material = material();
        PurchaseRequest purchaseRequest = purchaseRequest(planId);
        purchaseRequest.setRequestType(RequestType.SERVICE);
        purchaseRequestRepository.save(purchaseRequest);
        PurchaseRequestItem purchaseRequestItem = purchaseRequestItem(purchaseRequest, material);
        purchaseRequestItem.setLineType(ProcurementLineType.SERVICE);
        purchaseRequestItem.setServiceDescription("Workspace service");
        purchaseRequestItemRepository.save(purchaseRequestItem);
        demandPlanItem(planId, material, purchaseRequestItem);
        Supplier supplier = supplier();
        Contract contract = contract(supplier);
        PurchaseOrder purchaseOrder = purchaseOrder(purchaseRequest, supplier);
        purchaseOrder.setRequestType(RequestType.SERVICE);
        purchaseOrderRepository.save(purchaseOrder);
        ServiceOrder serviceOrder = serviceOrder(purchaseOrder, supplier, contract);
        PurchaseOrderItem purchaseOrderItem = purchaseOrderItem(purchaseOrder, material);
        purchaseOrderItem.setLineType(ProcurementLineType.SERVICE);
        purchaseOrderItem.setMaterialId(null);
        purchaseOrderItem.setServiceOrderId(serviceOrder.getId());
        purchaseOrderItem.setServiceDescription("Workspace service");
        purchaseOrderItemRepository.save(purchaseOrderItem);
        sourceAllocation(purchaseOrderItem, purchaseRequestItem, "10");
        serviceAcceptanceAct(purchaseOrder, serviceOrder, contract, "APPROVED");

        mockMvc.perform(get("/api/demand-plans/{id}/workspace", planId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].coverageStatus").value("SUPPLIED"))
            .andExpect(jsonPath("$.data.items[0].receivedOrAcceptedQuantity").value(10))
            .andExpect(jsonPath("$.data.supplySummary.status").value("SUPPLIED"))
            .andExpect(jsonPath("$.data.stages[?(@.key == 'PURCHASE_ORDER')].state", hasItem("COMPLETED")))
            .andExpect(jsonPath("$.data.stages[?(@.key == 'INBOUND_OR_EXECUTION')].state", hasItem("COMPLETED")))
            .andExpect(jsonPath("$.data.stages[?(@.key == 'RECEIPT_OR_ACCEPTANCE')].state", hasItem("COMPLETED")))
            .andExpect(jsonPath("$.data.relatedDocuments[*].documentType", hasItem("PURCHASE_ORDER")))
            .andExpect(jsonPath("$.data.relatedDocuments[*].documentType", hasItem("SERVICE_ACCEPTANCE_ACT")));
    }

    @Test
    void workspaceDetailMarksServiceAcceptanceReviewAsExecutionCompletedAndAcceptanceInProgress() throws Exception {
        String planId = createAsAdmin("Workspace service review plan");
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        DemandPlan plan = demandPlanRepository.findByIdAndIsDeletedFalse(UUID.fromString(planId)).orElseThrow();
        plan.setStatus("ACTIVE");
        plan.setRequestType(RequestType.SERVICE);
        demandPlanRepository.save(plan);
        Material material = material();
        PurchaseRequest purchaseRequest = purchaseRequest(planId);
        purchaseRequest.setRequestType(RequestType.SERVICE);
        purchaseRequestRepository.save(purchaseRequest);
        PurchaseRequestItem purchaseRequestItem = purchaseRequestItem(purchaseRequest, material);
        purchaseRequestItem.setLineType(ProcurementLineType.SERVICE);
        purchaseRequestItem.setServiceDescription("Workspace service");
        purchaseRequestItemRepository.save(purchaseRequestItem);
        demandPlanItem(planId, material, purchaseRequestItem);
        Supplier supplier = supplier();
        Contract contract = contract(supplier);
        PurchaseOrder purchaseOrder = purchaseOrder(purchaseRequest, supplier);
        purchaseOrder.setRequestType(RequestType.SERVICE);
        purchaseOrderRepository.save(purchaseOrder);
        ServiceOrder serviceOrder = serviceOrder(purchaseOrder, supplier, contract);
        PurchaseOrderItem purchaseOrderItem = purchaseOrderItem(purchaseOrder, material);
        purchaseOrderItem.setLineType(ProcurementLineType.SERVICE);
        purchaseOrderItem.setMaterialId(null);
        purchaseOrderItem.setServiceOrderId(serviceOrder.getId());
        purchaseOrderItem.setServiceDescription("Workspace service");
        purchaseOrderItemRepository.save(purchaseOrderItem);
        sourceAllocation(purchaseOrderItem, purchaseRequestItem, "10");
        serviceAcceptanceAct(purchaseOrder, serviceOrder, contract, "TECHNICAL_REVIEW");

        mockMvc.perform(get("/api/demand-plans/{id}/workspace", planId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].coverageStatus").value("ORDERED"))
            .andExpect(jsonPath("$.data.supplySummary.status").value("NOT_STARTED"))
            .andExpect(jsonPath("$.data.stages[?(@.key == 'PURCHASE_ORDER')].state", hasItem("COMPLETED")))
            .andExpect(jsonPath("$.data.stages[?(@.key == 'INBOUND_OR_EXECUTION')].state", hasItem("COMPLETED")))
            .andExpect(jsonPath("$.data.stages[?(@.key == 'RECEIPT_OR_ACCEPTANCE')].state", hasItem("IN_PROGRESS")))
            .andExpect(jsonPath("$.data.relatedDocuments[*].documentType", hasItem("SERVICE_ACCEPTANCE_ACT")));
    }

    @Test
    void workspaceDetailMarksWorkSuppliedAfterApprovedCompletionAct() throws Exception {
        String planId = createAsAdmin("Workspace work supplied plan");
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        DemandPlan plan = demandPlanRepository.findByIdAndIsDeletedFalse(UUID.fromString(planId)).orElseThrow();
        plan.setStatus("ACTIVE");
        plan.setRequestType(RequestType.SERVICE);
        demandPlanRepository.save(plan);
        Material material = material();
        PurchaseRequest purchaseRequest = purchaseRequest(planId);
        purchaseRequest.setRequestType(RequestType.SERVICE);
        purchaseRequestRepository.save(purchaseRequest);
        PurchaseRequestItem purchaseRequestItem = purchaseRequestItem(purchaseRequest, material);
        purchaseRequestItem.setLineType(ProcurementLineType.WORK);
        purchaseRequestItem.setServiceDescription("Workspace work");
        purchaseRequestItemRepository.save(purchaseRequestItem);
        demandPlanItem(planId, material, purchaseRequestItem);
        Supplier supplier = supplier();
        Contract contract = contract(supplier);
        PurchaseOrder purchaseOrder = purchaseOrder(purchaseRequest, supplier);
        purchaseOrder.setRequestType(RequestType.SERVICE);
        purchaseOrderRepository.save(purchaseOrder);
        PurchaseOrderItem purchaseOrderItem = purchaseOrderItem(purchaseOrder, material);
        purchaseOrderItem.setLineType(ProcurementLineType.WORK);
        purchaseOrderItem.setMaterialId(null);
        purchaseOrderItem.setServiceDescription("Workspace work");
        purchaseOrderItemRepository.save(purchaseOrderItem);
        sourceAllocation(purchaseOrderItem, purchaseRequestItem, "10");
        workCompletionAct(purchaseOrder, purchaseOrderItem, contract, "10", "APPROVED");

        mockMvc.perform(get("/api/demand-plans/{id}/workspace", planId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].coverageStatus").value("SUPPLIED"))
            .andExpect(jsonPath("$.data.items[0].receivedOrAcceptedQuantity").value(10))
            .andExpect(jsonPath("$.data.supplySummary.status").value("SUPPLIED"))
            .andExpect(jsonPath("$.data.stages[?(@.key == 'PURCHASE_ORDER')].state", hasItem("COMPLETED")))
            .andExpect(jsonPath("$.data.stages[?(@.key == 'INBOUND_OR_EXECUTION')].state", hasItem("COMPLETED")))
            .andExpect(jsonPath("$.data.stages[?(@.key == 'RECEIPT_OR_ACCEPTANCE')].state", hasItem("COMPLETED")))
            .andExpect(jsonPath("$.data.relatedDocuments[*].documentType", hasItem("PURCHASE_ORDER")))
            .andExpect(jsonPath("$.data.relatedDocuments[*].documentType", hasItem("WORK_COMPLETION_ACT")));
    }

    @Test
    void workspaceSummaryCountMatchesStatusFilteredListInvariant() throws Exception {
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        Material material = material();

        // Plan 1: DRAFT (Pending status bucket)
        createAsAdmin(planBodyWithItem(ownDepartment.getId(), "Draft Plan 1", material));

        // Plan 2: DRAFT (Pending status bucket)
        createAsAdmin(planBodyWithItem(ownDepartment.getId(), "Draft Plan 2", material));

        // Plan 3: ACTIVE (Approved status bucket)
        String planId3 = createAsAdmin(planBodyWithItem(ownDepartment.getId(), "Active Plan 3", material));
        activatePlan(planId3);

        // Verify summary reports 2 for PENDING and 1 for ACTIVE
        mockMvc.perform(get("/api/demand-plans/workspace/summary")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.rows[?(@.status == 'DRAFT')].items[0].planCount", hasItem(2)))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'ACTIVE')].items[0].planCount", hasItem(1)));

        // Filtering with materialId + PENDING status list (DRAFT, COLLECTING, SUBMITTED) must return exactly 2 elements
        mockMvc.perform(get("/api/demand-plans/workspace")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId)
                .param("materialId", material.getId().toString())
                .param("status", "DRAFT", "COLLECTING", "SUBMITTED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(2));

        // Filtering with materialId + APPROVED status list (APPROVED, ACTIVE, CLOSED) must return exactly 1 element
        mockMvc.perform(get("/api/demand-plans/workspace")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId)
                .param("materialId", material.getId().toString())
                .param("status", "APPROVED", "ACTIVE", "CLOSED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    private String createAsAdmin(String name) throws Exception {
        return createAsAdmin(planBody(ownDepartment.getId(), name));
    }

    private String createAsAdmin(Map<String, Object> body) throws Exception {
        String response = mockMvc.perform(post("/api/demand-plans")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode data = objectMapper.readTree(response).path("data");
        return data.path("id").asText();
    }

    private void activatePlan(String planId) {
        DemandPlan plan = demandPlanRepository.findByIdAndIsDeletedFalse(UUID.fromString(planId)).orElseThrow();
        plan.setStatus("ACTIVE");
        demandPlanRepository.save(plan);
    }

    private Map<String, Object> planBody(UUID departmentId, String name) {
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("code", "DP-" + suffix());
        body.put("name", name);
        body.put("requestType", "GOODS");
        body.put("planYear", Year.now().getValue());
        body.put("periodType", "YEAR");
        body.put("periodStart", Year.now().getValue() + "-01-01");
        body.put("periodEnd", Year.now().getValue() + "-12-31");
        if (departmentId != null) {
            body.put("departmentId", departmentId.toString());
        }
        body.put("ownerUsername", "department-originator");
        body.put("currencyCode", "UZS");
        body.put("totalPlannedAmount", 0);
        body.put("totalApprovedAmount", 0);
        body.put("totalReservedQuantity", 0);
        return body;
    }

    private Map<String, Object> planBodyWithItem(UUID departmentId, String name, Material material) {
        Map<String, Object> body = planBody(departmentId, name);
        java.util.LinkedHashMap<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("name", "Workspace amount item");
        item.put("materialId", material.getId().toString());
        item.put("requiredDate", Year.now().getValue() + "-08-31");
        item.put("demandType", DemandType.OPERATIONS.name());
        item.put("priority", DemandPriority.HIGH.name());
        item.put("requestedQuantity", 7);
        item.put("estimatedUnitPrice", 100000);
        item.put("currencyCode", "UZS");
        body.put("items", java.util.List.of(item));
        return body;
    }

    private DemandPlanningCycle demandPlanningCycle(String name) {
        DemandPlanningCycle cycle = new DemandPlanningCycle();
        cycle.setCode("DPC-" + suffix());
        cycle.setName(name);
        cycle.setStatus("DRAFT");
        cycle.setPlanYear(Year.now().getValue());
        cycle.setPeriodType("YEAR");
        cycle.setPeriodStart(LocalDate.of(Year.now().getValue(), 1, 1));
        cycle.setPeriodEnd(LocalDate.of(Year.now().getValue(), 12, 31));
        cycle.setOwnerUsername("department-originator");
        cycle.setCurrencyCode("UZS");
        return demandPlanningCycleRepository.save(cycle);
    }

    private Department department(String label) {
        Department department = new Department();
        department.setCode("DEP-" + label + "-" + suffix());
        department.setName(label + " department");
        department.setStatus("ACTIVE");
        department.setDepartmentType("OPERATIONS");
        return departmentRepository.save(department);
    }

    private Employee employee(String username, Department department) {
        Employee employee = new Employee();
        employee.setCode("EMP-" + suffix());
        employee.setName(username);
        employee.setStatus("ACTIVE");
        employee.setPersonnelNumber(username);
        employee.setDepartment(department);
        return employeeRepository.save(employee);
    }

    private Material material() {
        Material material = new Material();
        material.setCode("MAT-" + suffix());
        material.setName("Workspace material");
        material.setStatus("ACTIVE");
        return materialRepository.save(material);
    }

    private Material localizedUnitMaterial() {
        UnitOfMeasure unit = new UnitOfMeasure();
        unit.setCode("UOM-" + suffix());
        unit.setName("Piece");
        unit.setStatus("ACTIVE");
        unit.setMeasurementNameLong("Pieces");
        unit.setMeasurementNameLongRu("Штуки");
        unit.setMeasurementNameLongUz("Dona");
        unit.setMeasurementNameShort("pcs");
        UnitOfMeasure savedUnit = unitOfMeasureRepository.save(unit);

        Material material = new Material();
        material.setCode("MAT-" + suffix());
        material.setName("Workspace localized unit material");
        material.setStatus("ACTIVE");
        material.setUnitOfMeasureId(savedUnit.getId());
        return materialRepository.save(material);
    }

    private PurchaseRequest purchaseRequest(String planId) {
        PurchaseRequest request = new PurchaseRequest();
        request.setCode("PR-" + suffix());
        request.setName("Workspace purchase request");
        request.setStatus("APPROVED");
        request.setDepartmentId(ownDepartment.getId());
        request.setDemandPlanId(UUID.fromString(planId));
        request.setRequestedBy("department-originator");
        request.setRequestType(RequestType.GOODS);
        request.setRequiredDate(LocalDate.now().plusDays(10));
        request.setTotalAmount(new BigDecimal("100"));
        request.setCurrencyCode("UZS");
        return purchaseRequestRepository.save(request);
    }

    private PurchaseRequestItem purchaseRequestItem(PurchaseRequest request, Material material) {
        PurchaseRequestItem item = new PurchaseRequestItem();
        item.setCode("PRI-" + suffix());
        item.setName("Workspace purchase request item");
        item.setStatus("OPEN");
        item.setPurchaseRequestId(request.getId());
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialId(material.getId());
        item.setQuantity(new BigDecimal("10"));
        item.setEstimatedPrice(BigDecimal.TEN);
        return purchaseRequestItemRepository.save(item);
    }

    private DemandPlanItem demandPlanItem(String planId, Material material, PurchaseRequestItem purchaseRequestItem) {
        DemandPlanItem item = new DemandPlanItem();
        item.setCode("DPI-" + suffix());
        item.setName("Workspace demand item");
        item.setStatus("APPROVED");
        item.setDemandPlanId(UUID.fromString(planId));
        item.setDepartmentId(ownDepartment.getId());
        item.setMaterialId(material.getId());
        item.setDemandType(DemandType.PROJECT.name());
        item.setPriority(DemandPriority.NORMAL.name());
        item.setRequestedQuantity(new BigDecimal("10"));
        item.setApprovedQuantity(new BigDecimal("10"));
        item.setReservedQuantity(BigDecimal.ZERO);
        item.setEstimatedUnitPrice(BigDecimal.TEN);
        item.setEstimatedAmount(new BigDecimal("100"));
        item.setCurrencyCode("UZS");
        item.setRequiredDate(LocalDate.now().plusDays(10));
        item.setPurchaseRequestItemId(purchaseRequestItem == null ? null : purchaseRequestItem.getId());
        return demandPlanItemRepository.save(item);
    }

    private Supplier supplier() {
        Supplier supplier = new Supplier();
        supplier.setCode("SUP-" + suffix());
        supplier.setName("Workspace supplier");
        supplier.setShortName("Workspace supplier");
        supplier.setLegalName("Workspace supplier LLC");
        supplier.setStatus("ACTIVE");
        return supplierRepository.save(supplier);
    }

    private PurchaseOrder purchaseOrder(PurchaseRequest purchaseRequest, Supplier supplier) {
        PurchaseOrder order = new PurchaseOrder();
        order.setCode("PO-" + suffix());
        order.setName("Workspace purchase order");
        order.setStatus("SENT");
        order.setSupplierId(supplier.getId());
        order.setPurchaseRequestId(purchaseRequest.getId());
        order.setRequestType(RequestType.GOODS);
        order.setOrderDate(LocalDate.now());
        order.setCurrencyCode("UZS");
        order.setTotalAmount(new BigDecimal("100"));
        return purchaseOrderRepository.save(order);
    }

    private PurchaseOrderItem purchaseOrderItem(PurchaseOrder purchaseOrder, Material material) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setCode("POI-" + suffix());
        item.setName("Workspace purchase order item");
        item.setStatus("ACTIVE");
        item.setPurchaseOrderId(purchaseOrder.getId());
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialId(material.getId());
        item.setQuantity(new BigDecimal("10"));
        item.setUnitPrice(BigDecimal.TEN);
        return purchaseOrderItemRepository.save(item);
    }

    private Contract contract(Supplier supplier) {
        Contract contract = new Contract();
        contract.setCode("CON-" + suffix());
        contract.setName("Workspace contract");
        contract.setStatus("ACTIVE");
        contract.setSupplierId(supplier.getId());
        contract.setContractNumber("CN-" + suffix());
        contract.setStartDate(LocalDate.now().minusDays(1));
        contract.setEndDate(LocalDate.now().plusDays(30));
        contract.setLimitAmount(new BigDecimal("1000"));
        contract.setCurrencyCode("UZS");
        return contractRepository.save(contract);
    }

    private ServiceOrder serviceOrder(PurchaseOrder purchaseOrder, Supplier supplier, Contract contract) {
        ServiceOrder order = new ServiceOrder();
        order.setCode("SO-" + suffix());
        order.setName("Workspace service order");
        order.setStatus("ACTIVE");
        order.setSupplierId(supplier.getId());
        order.setContractId(contract.getId());
        order.setPurchaseOrderId(purchaseOrder.getId());
        order.setServiceType("SERVICE");
        order.setTotalAmount(new BigDecimal("100"));
        order.setCurrencyCode("UZS");
        return serviceOrderRepository.save(order);
    }

    private void sourceAllocation(PurchaseOrderItem purchaseOrderItem, PurchaseRequestItem purchaseRequestItem, String quantity) {
        PurchaseOrderSourceAllocation allocation = new PurchaseOrderSourceAllocation();
        allocation.setCode("POSRC-" + suffix());
        allocation.setName("Workspace source allocation");
        allocation.setStatus("ACTIVE");
        allocation.setPurchaseOrderItemId(purchaseOrderItem.getId());
        allocation.setPurchaseRequestItemId(purchaseRequestItem.getId());
        allocation.setOrderedQuantity(new BigDecimal(quantity));
        sourceAllocationRepository.save(allocation);
    }

    private void goodsReceipt(
        PurchaseOrder purchaseOrder,
        PurchaseOrderItem purchaseOrderItem,
        Supplier supplier,
        Material material,
        Warehouse warehouse,
        String acceptedQuantity
    ) {
        GoodsReceipt receipt = new GoodsReceipt();
        receipt.setCode("GR-" + suffix());
        receipt.setName("Workspace goods receipt");
        receipt.setStatus("POSTED");
        receipt.setPurchaseOrderId(purchaseOrder.getId());
        receipt.setSupplierId(supplier.getId());
        receipt.setWarehouseId(warehouse.getId());
        receipt.setCurrencyCode("UZS");
        receipt.setExchangeRate(BigDecimal.ONE);
        receipt.setBaseCurrencyCode("UZS");
        receipt.setAcceptedQuantity(new BigDecimal(acceptedQuantity));
        receipt.setAcceptedAmount(new BigDecimal(acceptedQuantity).multiply(BigDecimal.TEN));
        receipt.setPostedAt(LocalDateTime.now());
        receipt.setPostedBy("warehouse-user");
        GoodsReceipt saved = goodsReceiptRepository.save(receipt);

        GoodsReceiptLine line = new GoodsReceiptLine();
        line.setCode("GRL-" + suffix());
        line.setName("Workspace goods receipt line");
        line.setStatus("POSTED");
        line.setGoodsReceiptId(saved.getId());
        line.setPurchaseOrderId(purchaseOrder.getId());
        line.setPurchaseOrderItemId(purchaseOrderItem.getId());
        line.setSupplierId(supplier.getId());
        line.setMaterialId(material.getId());
        line.setWarehouseId(warehouse.getId());
        line.setOpenQuantityBefore(new BigDecimal("10"));
        line.setAcceptedQuantity(new BigDecimal(acceptedQuantity));
        line.setAccreditationStatus("APPROVED");
        goodsReceiptLineRepository.save(line);
    }

    private void serviceAcceptanceAct(
        PurchaseOrder purchaseOrder,
        ServiceOrder serviceOrder,
        Contract contract,
        String status
    ) {
        ServiceAcceptanceAct act = new ServiceAcceptanceAct();
        act.setCode("SAA-" + suffix());
        act.setName("Workspace service acceptance");
        act.setStatus(status);
        act.setServiceOrderId(serviceOrder.getId());
        act.setPurchaseOrderId(purchaseOrder.getId());
        act.setContractId(contract.getId());
        act.setAcceptedAt(LocalDateTime.now());
        act.setAcceptedBy("service-acceptor");
        act.setAmount(new BigDecimal("100"));
        act.setPeriodStart(LocalDate.now().minusDays(1));
        act.setPeriodEnd(LocalDate.now());
        serviceAcceptanceActRepository.save(act);
    }

    private void workCompletionAct(
        PurchaseOrder purchaseOrder,
        PurchaseOrderItem purchaseOrderItem,
        Contract contract,
        String completedQuantity,
        String status
    ) {
        WorkCompletionAct act = new WorkCompletionAct();
        act.setCode("WCA-" + suffix());
        act.setName("Workspace work completion");
        act.setStatus(status);
        act.setPurchaseOrderId(purchaseOrder.getId());
        act.setPurchaseOrderItemId(purchaseOrderItem.getId());
        act.setContractId(contract.getId());
        act.setWorkLocation("Workspace site");
        act.setPeriodStart(LocalDate.now().minusDays(1));
        act.setPeriodEnd(LocalDate.now());
        act.setCompletedQuantity(new BigDecimal(completedQuantity));
        act.setAmount(new BigDecimal(completedQuantity).multiply(BigDecimal.TEN));
        act.setInspectionResult(WorkInspectionResult.PASSED);
        act.setAcceptedAt(LocalDateTime.now());
        act.setAcceptedBy("work-acceptor");
        workCompletionActRepository.save(act);
    }

    private void issuedLine(
        DemandPlanItem demandItem,
        Material material,
        Warehouse warehouse,
        User requester,
        User receiver,
        String issuedQuantity
    ) {
        IssueRequest request = new IssueRequest();
        request.setCode("IR-" + suffix());
        request.setName("Workspace issue request");
        request.setStatus(IssueRequestStatus.ISSUED.name());
        request.setWarehouseId(warehouse.getId());
        request.setRequesterUserId(requester.getId());
        request.setReceiverUserId(receiver.getId());
        request.setCostObjectType(IssueRequestCostObjectType.DEPARTMENT);
        request.setCostObjectId(ownDepartment.getId());
        request.setIssuedDate(LocalDate.now());
        request.setUpdatedBy(receiver.getUsername());
        IssueRequest savedRequest = issueRequestRepository.save(request);

        IssueRequestLine line = new IssueRequestLine();
        line.setCode("IRL-" + suffix());
        line.setName("Workspace issue request line");
        line.setStatus("ISSUED");
        line.setIssueRequestId(savedRequest.getId());
        line.setMaterialId(material.getId());
        line.setRequestedQuantity(new BigDecimal("10"));
        line.setApprovedQuantity(new BigDecimal("10"));
        line.setIssuedQuantity(new BigDecimal(issuedQuantity));
        line.setSourceDemandPlanItemId(demandItem.getId());
        issueRequestLineRepository.save(line);
    }

    private Warehouse warehouse() {
        Warehouse warehouse = new Warehouse();
        warehouse.setCode("WH-" + suffix());
        warehouse.setName("Workspace warehouse");
        warehouse.setStatus("ACTIVE");
        warehouse.setAddress("Workspace address");
        return warehouseRepository.save(warehouse);
    }

    private User appUser(String username) {
        User user = new User();
        user.setCode("USR-" + suffix());
        user.setName(username);
        user.setUsername(username);
        user.setPasswordHash("test");
        user.setFullName(username);
        user.setStatus("ACTIVE");
        user.setEnabled(true);
        user.setSuperAdmin(true);
        user.getRoles().add(RoleName.ADMIN);
        return userRepository.save(user);
    }

    private ApprovalFlow approvalFlow() {
        ApprovalFlow flow = new ApprovalFlow();
        flow.setCode("AF-" + suffix());
        flow.setName("Demand plan approval flow");
        flow.setStatus("ACTIVE");
        flow.setModuleKey("DEMAND_PLAN");
        flow.setFlowType(ApprovalFlowType.SEQUENTIAL);
        flow.setRequestType(ApprovalRequestType.GOODS);
        flow.setActive(true);
        return approvalFlowRepository.save(flow);
    }

    private ApprovalInstance approvalInstance(ApprovalFlow flow, UUID planId) {
        ApprovalInstance instance = new ApprovalInstance();
        instance.setCode("AI-" + suffix());
        instance.setName("Demand plan approval");
        instance.setStatus(ApprovalInstanceStatus.PENDING.name());
        instance.setApprovalFlowId(flow.getId());
        instance.setModuleKey("DEMAND_PLAN");
        instance.setEntityId(planId);
        instance.setApprovalStatus(ApprovalInstanceStatus.PENDING);
        instance.setCurrentStepOrder(2);
        instance.setStartedAt(LocalDateTime.now().minusHours(1));
        return approvalInstanceRepository.save(instance);
    }

    private ApprovalTask approvalTask(
        ApprovalInstance instance,
        int stepOrder,
        ApprovalTaskStatus status,
        String assigneeRole,
        String actedBy
    ) {
        ApprovalTask task = new ApprovalTask();
        task.setCode("AT-" + suffix());
        task.setName("Step " + stepOrder);
        task.setStatus(status.name());
        task.setApprovalInstanceId(instance.getId());
        task.setStepOrder(stepOrder);
        task.setAssigneeType(ApprovalAssigneeType.ROLE);
        task.setAssigneeRole(assigneeRole);
        task.setTaskStatus(status);
        task.setActedBy(actedBy);
        task.setActedAt(actedBy == null ? null : LocalDateTime.now().minusMinutes(30));
        task.setCommentText(actedBy == null ? null : "Looks good");
        return approvalTaskRepository.save(task);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }
}
