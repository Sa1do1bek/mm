package com.enterprise.mm;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.DemandPlanItem;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.enums.DemandPriority;
import com.enterprise.mm.enums.DemandType;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.repository.DemandPlanItemRepository;
import com.enterprise.mm.repository.DemandPlanRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.seed.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PurchaseRequestWorkspaceFoundationTest {
    @Autowired MockMvc mockMvc;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired DemandPlanRepository demandPlanRepository;
    @Autowired DemandPlanItemRepository demandPlanItemRepository;
    @Autowired MaterialRepository materialRepository;
    @Autowired PurchaseRequestRepository purchaseRequestRepository;
    @Autowired PurchaseRequestItemRepository purchaseRequestItemRepository;

    private String tenantId;
    private Department department;

    @BeforeEach
    void setUp() {
        tenantId = "pr-workspace-" + UUID.randomUUID();
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        department = department("OWN");
        employee("department-originator", department);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void workspaceListsPurchaseRequestWithDemandPlanLineage() throws Exception {
        Material material = material();
        DemandPlan demandPlan = demandPlan();
        PurchaseRequest purchaseRequest = purchaseRequest(demandPlan);
        PurchaseRequestItem purchaseRequestItem = purchaseRequestItem(purchaseRequest, material);
        demandPlanItem(demandPlan, material, purchaseRequestItem);

        mockMvc.perform(get("/api/purchase-requests/workspace")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].demandPlanId").value(demandPlan.getId().toString()))
            .andExpect(jsonPath("$.data.content[0].demandPlanCode").value(demandPlan.getCode()))
            .andExpect(jsonPath("$.data.content[0].currentProcessStage").exists());

        mockMvc.perform(get("/api/purchase-requests/{id}/workspace", purchaseRequest.getId())
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.header.demandPlanId").value(demandPlan.getId().toString()))
            .andExpect(jsonPath("$.data.header.demandPlanCode").value(demandPlan.getCode()))
            .andExpect(jsonPath("$.data.items[0].sourceDemandPlanItemId").exists())
            .andExpect(jsonPath("$.data.relatedDocuments[*].documentType", hasItem("DEMAND_PLAN")))
            .andExpect(jsonPath("$.data.stages[*].key", hasItem("DEMAND_PLAN")))
            .andExpect(jsonPath("$.data.stages[*].key", hasItem("PURCHASE_REQUEST")))
            .andExpect(jsonPath("$.data.availableActions.secondary[*].key", not(hasItem("OPEN_PROCUREMENT_COMMAND"))));
    }

    @Test
    void workspaceSummaryAggregatesPurchaseRequestsWithSameFilters() throws Exception {
        Material material = material();
        DemandPlan demandPlan = demandPlan();
        PurchaseRequest firstRequest = purchaseRequest(demandPlan);
        PurchaseRequest secondRequest = purchaseRequest(demandPlan);
        PurchaseRequest draftRequest = purchaseRequest(demandPlan);
        draftRequest.setStatus("DRAFT");
        purchaseRequestRepository.save(draftRequest);
        purchaseRequestItem(firstRequest, material);
        purchaseRequestItem(secondRequest, material);
        purchaseRequestItem(draftRequest, material);

        mockMvc.perform(get("/api/purchase-requests/workspace/summary")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total.count").value(3))
            .andExpect(jsonPath("$.data.total.plannedAmount").value(300))
            .andExpect(jsonPath("$.data.total.currencyCode").value("UZS"))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'APPROVED')].count", hasItem(2)))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'APPROVED')].plannedAmount", hasItem(200.0)))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'APPROVED')].items[0].itemType", hasItem("MATERIAL")))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'APPROVED')].items[0].materialId", hasItem(material.getId().toString())))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'APPROVED')].items[0].requestedQuantity", hasItem(20.0)))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'APPROVED')].items[0].plannedAmount", hasItem(200.0)))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'APPROVED')].items[0].requestCount", hasItem(2)));

        mockMvc.perform(get("/api/purchase-requests/workspace/summary")
                .param("status", "DRAFT")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total.count").value(1))
            .andExpect(jsonPath("$.data.rows[0].status").value("DRAFT"));
    }

    @Test
    void procurementCaseAdvancesApprovedPurchaseRequestToProcurementMethod() throws Exception {
        Material material = material();
        DemandPlan demandPlan = demandPlan();
        PurchaseRequest purchaseRequest = purchaseRequest(demandPlan);
        PurchaseRequestItem purchaseRequestItem = purchaseRequestItem(purchaseRequest, material);
        demandPlanItem(demandPlan, material, purchaseRequestItem);

        mockMvc.perform(get("/api/procurement-cases/PURCHASE_REQUEST/{id}", purchaseRequest.getId())
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.currentStage").value("PROCUREMENT_METHOD"))
            .andExpect(jsonPath("$.data.stages[?(@.key == 'PURCHASE_REQUEST')].state", hasItem("COMPLETED")))
            .andExpect(jsonPath("$.data.stages[?(@.key == 'PR_APPROVAL')].state", hasItem("COMPLETED")))
            .andExpect(jsonPath("$.data.stages[?(@.key == 'PROCUREMENT_METHOD')].state", hasItem("IN_PROGRESS")))
            .andExpect(jsonPath("$.data.stages[?(@.key == 'PURCHASE_ORDER')].state", hasItem("UNSTARTED")));
    }

    @Test
    void procurementCaseShowsPurchaseRequestStepReadyBeforeRequestExists() throws Exception {
        Material material = material();
        DemandPlan demandPlan = demandPlan();
        demandPlanItem(demandPlan, material, null);

        mockMvc.perform(get("/api/procurement-cases/DEMAND_PLAN/{id}", demandPlan.getId())
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.currentStage").value("PURCHASE_REQUEST"))
            .andExpect(jsonPath("$.data.stages[?(@.key == 'DEMAND_PLAN')].state", hasItem("COMPLETED")))
            .andExpect(jsonPath("$.data.stages[?(@.key == 'PLAN_APPROVAL')].state", hasItem("COMPLETED")))
            .andExpect(jsonPath("$.data.stages[?(@.key == 'PURCHASE_REQUEST')].state", hasItem("IN_PROGRESS")));
    }

    private Department department(String label) {
        Department entity = new Department();
        entity.setCode("DEP-" + label + "-" + suffix());
        entity.setName(label + " department");
        entity.setStatus("ACTIVE");
        entity.setDepartmentType("OPERATIONS");
        return departmentRepository.save(entity);
    }

    private Employee employee(String username, Department departmentEntity) {
        Employee employee = new Employee();
        employee.setCode("EMP-" + suffix());
        employee.setName(username);
        employee.setStatus("ACTIVE");
        employee.setPersonnelNumber(username);
        employee.setDepartment(departmentEntity);
        return employeeRepository.save(employee);
    }

    private Material material() {
        Material entity = new Material();
        entity.setCode("MAT-" + suffix());
        entity.setName("Workspace material");
        entity.setStatus("ACTIVE");
        return materialRepository.save(entity);
    }

    private DemandPlan demandPlan() {
        DemandPlan plan = new DemandPlan();
        plan.setCode("DP-" + suffix());
        plan.setName("Workspace demand plan");
        plan.setStatus("ACTIVE");
        plan.setRequestType(RequestType.GOODS);
        plan.setPlanYear(Year.now().getValue());
        plan.setPeriodType("YEAR");
        plan.setPeriodStart(LocalDate.of(Year.now().getValue(), 1, 1));
        plan.setPeriodEnd(LocalDate.of(Year.now().getValue(), 12, 31));
        plan.setDepartment(department);
        plan.setOwnerUsername("department-originator");
        plan.setCurrencyCode("UZS");
        plan.setTotalPlannedAmount(BigDecimal.ZERO);
        plan.setTotalApprovedAmount(BigDecimal.ZERO);
        plan.setTotalReservedQuantity(BigDecimal.ZERO);
        return demandPlanRepository.save(plan);
    }

    private PurchaseRequest purchaseRequest(DemandPlan demandPlan) {
        PurchaseRequest request = new PurchaseRequest();
        request.setCode("PR-" + suffix());
        request.setName("Workspace purchase request");
        request.setStatus("APPROVED");
        request.setDepartmentId(department.getId());
        request.setDemandPlanId(demandPlan.getId());
        request.setRequestedBy("department-originator");
        request.setRequestType(RequestType.GOODS);
        request.setRequiredDate(LocalDate.now().plusDays(10));
        request.setTotalAmount(new BigDecimal("100"));
        request.setCurrencyCode("UZS");
        return purchaseRequestRepository.save(request);
    }

    private PurchaseRequestItem purchaseRequestItem(PurchaseRequest request, Material materialEntity) {
        PurchaseRequestItem item = new PurchaseRequestItem();
        item.setCode("PRI-" + suffix());
        item.setName("Workspace purchase request item");
        item.setStatus("OPEN");
        item.setPurchaseRequestId(request.getId());
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialId(materialEntity.getId());
        item.setQuantity(new BigDecimal("10"));
        item.setEstimatedPrice(BigDecimal.TEN);
        return purchaseRequestItemRepository.save(item);
    }

    private DemandPlanItem demandPlanItem(DemandPlan plan, Material materialEntity, PurchaseRequestItem purchaseRequestItem) {
        DemandPlanItem item = new DemandPlanItem();
        item.setCode("DPI-" + suffix());
        item.setName("Workspace demand item");
        item.setStatus("APPROVED");
        item.setDemandPlanId(plan.getId());
        item.setDepartmentId(department.getId());
        item.setMaterialId(materialEntity.getId());
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

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
