package com.enterprise.mm;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.IssueRequestCostObjectType;
import com.enterprise.mm.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "app.seed.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DepartmentHeadPermissionsTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DepartmentRepository departments;
    @Autowired CompanyRepository companies;
    @Autowired UserRepository users;
    @Autowired WarehouseRepository warehouses;
    @Autowired MaterialRepository materials;
    @Autowired MaterialGroupRepository groups;
    @Autowired UnitOfMeasureRepository units;
    @Autowired DemandPlanRepository plans;
    @Autowired MaterialReservationRepository reservations;
    @Autowired IssueRequestRepository issues;
    @Autowired MaterialReturnDocumentRepository returns;
    @Autowired PurchaseRequestRepository requests;
    @Autowired FixedAssetRepository assets;
    @Autowired AssetHandoverRepository handovers;
    @Autowired DepartmentWarehouseAccessRepository warehouseAccess;

    private String tenant;
    private UUID companyId;
    private Department own;
    private Department other;
    private User head;
    private Warehouse warehouse;
    private Material material;

    @BeforeEach
    void setUp() {
        tenant = "department-head-" + UUID.randomUUID();
        TenantContext.setTenant(tenant);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        companyId = companies.saveAndFlush(named(new Company(), "Company")).getId();
        CompanyContext.setCompany(companyId);
        own = departments.save(named(new Department(), "Own department"));
        other = departments.save(named(new Department(), "Other department"));
        head = named(new User(), "Head");
        head.setUsername("department-head");
        head.setPasswordHash("unused-test-password");
        head.setDepartment(own);
        head = users.save(head);
        warehouse = warehouses.save(named(new Warehouse(), "Warehouse"));
        var group = groups.save(named(new MaterialGroup(), "Group"));
        var unit = units.save(named(new UnitOfMeasure(), "Unit"));
        material = named(new Material(), "Material");
        material.setMaterialGroupId(group.getId());
        material.setUnitOfMeasureId(unit.getId());
        material = materials.save(material);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEPARTMENT_WORKER"})
    void unrelatedCompanyRegistriesAreForbiddenEvenByDirectApi(String role) throws Exception {
        for (String path : List.of("/api/rfq", "/api/consolidated-demands", "/api/procurement/method-decisions",
            "/api/tender/commissions", "/api/supplier-blacklist", "/api/valuation", "/api/inventory-valuation/cost-layers",
            "/api/integrations/events", "/api/migration", "/api/hr/employees", "/api/non-moving-stock",
            "/api/illiquid-stock", "/api/qms/criteria", "/api/stock/balances", "/api/inventory/optimization",
            "/api/workspaces/warehouse-execution/overview", "/api/warehouse/transfers", "/api/quality/inspections",
            "/api/asset-acceptances", "/api/asset-verifications", "/api/contract-invoices", "/api/contract-payments")) {
            mvc.perform(get(path).with(user("department-head").roles(role)).header("X-Tenant-Id", tenant).header("X-Company-Id", companyId))
                .andExpect(status().isForbidden());
        }
        for (String path : List.of("/api/demand-planning-cycles", "/api/consolidated-demands", "/api/material-reservations",
            "/api/demand-plan-items/" + UUID.randomUUID() + "/reserve")) {
            mvc.perform(post(path).with(user("department-head").roles(role)).header("X-Tenant-Id", tenant).header("X-Company-Id", companyId)
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void requiredLookupsAndAssignedWorkRemainReachable() throws Exception {
        for (String path : List.of("/api/materials", "/api/uom", "/api/departments", "/api/warehouses",
            "/api/demand-planning-cycles", "/api/purchase-requests", "/api/purchase-orders", "/api/approval-tasks/my",
            "/api/service-requests", "/api/suppliers", "/api/workspaces/warehouse-execution/selectors/users")) {
            mvc.perform(get(path).with(user("department-head").roles("DEPARTMENT_HEAD")).header("X-Tenant-Id", tenant).header("X-Company-Id", companyId))
                .andExpect(status().isOk());
        }
        mvc.perform(get("/api/inventory/" + UUID.randomUUID() + "/workspace")
                .with(user("department-head").roles("DEPARTMENT_HEAD")).header("X-Tenant-Id", tenant).header("X-Company-Id", companyId))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/inventory-valuation/cost-layers")
                .with(user("department-head").roles("DEPARTMENT_HEAD", "FINANCE_CONTROLLER"))
                .header("X-Tenant-Id", tenant).header("X-Company-Id", companyId))
            .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEPARTMENT_WORKER"})
    void reservationsAreScopedBeforePaginationAndDirectReads(String role) throws Exception {
        var ownReservation = reservation(plan(own));
        var foreignReservation = reservation(plan(other));
        reservation(null);
        var otherWarehouse = warehouses.save(named(new Warehouse(), "Unassigned warehouse"));
        var inaccessibleReservation = reservation(plan(other));
        inaccessibleReservation.setWarehouseId(otherWarehouse.getId());
        reservations.save(inaccessibleReservation);
        mvc.perform(get("/api/material-reservations").param("size", "1")
                .with(user("department-head").roles(role)).header("X-Tenant-Id", tenant).header("X-Company-Id", companyId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(ownReservation.getId().toString()));
        mvc.perform(get("/api/material-reservations/" + foreignReservation.getId())
                .with(user("department-head").roles(role)).header("X-Tenant-Id", tenant).header("X-Company-Id", companyId))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/material-reservations/" + ownReservation.getId())
                .with(user("department-head").roles(role)).header("X-Tenant-Id", tenant).header("X-Company-Id", companyId))
            .andExpect(status().isOk());
        mvc.perform(get("/api/material-reservations").with(user("unassigned").roles(role)).header("X-Tenant-Id", tenant).header("X-Company-Id", companyId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        var grant = new DepartmentWarehouseAccess();
        grant.setTenantId(tenant);
        grant.setCompanyId(companyId);
        grant.setDepartment(own);
        grant.setWarehouse(warehouse);
        warehouseAccess.saveAndFlush(grant);
        mvc.perform(get("/api/material-reservations")
                .with(user("department-head").roles(role, "WAREHOUSE_HEAD"))
                .header("X-Tenant-Id", tenant).header("X-Company-Id", companyId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(3));
        mvc.perform(get("/api/material-reservations/" + inaccessibleReservation.getId())
                .with(user("department-head").roles(role, "WAREHOUSE_HEAD"))
                .header("X-Tenant-Id", tenant).header("X-Company-Id", companyId))
            .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEPARTMENT_WORKER"})
    void returnsFollowTheOwningIssueDepartmentIncludingCommands(String role) throws Exception {
        var ownReturn = returned(issue(own));
        var foreignReturn = returned(issue(other));
        var historical = returned(null);
        mvc.perform(get("/api/returns").param("size", "1").with(user("department-head").roles(role))
                .header("X-Tenant-Id", tenant).header("X-Company-Id", companyId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(ownReturn.getId().toString()));
        for (var denied : List.of(foreignReturn, historical)) {
            mvc.perform(get("/api/returns/" + denied.getId()).with(user("department-head").roles(role))
                    .header("X-Tenant-Id", tenant).header("X-Company-Id", companyId)).andExpect(status().isNotFound());
            mvc.perform(post("/api/returns/" + denied.getId() + "/submit").with(user("department-head").roles(role))
                    .header("X-Tenant-Id", tenant).header("X-Company-Id", companyId)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/returns/" + ownReturn.getId()).with(user("department-head").roles(role))
                .header("X-Tenant-Id", tenant).header("X-Company-Id", companyId)).andExpect(status().isOk());
        mvc.perform(get("/api/returns").param("issueRequestId", foreignReturn.getIssueRequestId().toString())
                .with(user("department-head").roles(role)).header("X-Tenant-Id", tenant).header("X-Company-Id", companyId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(post("/api/returns").with(user("department-head").roles(role)).header("X-Tenant-Id", tenant).header("X-Company-Id", companyId)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                    "warehouseId", warehouse.getId(), "originType", "HISTORICAL_1C", "historicalDocumentNumber", "old-1",
                    "lines", List.of(Map.of("materialId", material.getId(), "returnQuantity", 1, "condition", "USABLE"))))))
            .andExpect(status().isForbidden());
    }

    @Test
    void workerDoesNotGainWarehouseHandoverAccessFromDepartmentGrants() throws Exception {
        var ownHandover = handover(own);
        var foreignHandover = handover(other);
        var grant = new DepartmentWarehouseAccess();
        grant.setTenantId(tenant); grant.setCompanyId(companyId); grant.setDepartment(own); grant.setWarehouse(warehouse);
        warehouseAccess.saveAndFlush(grant);
        mvc.perform(get("/api/asset-handovers").with(user("department-head").roles("DEPARTMENT_WORKER"))
                .header("X-Tenant-Id", tenant).header("X-Company-Id", companyId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(ownHandover.getId().toString()));
        mvc.perform(get("/api/asset-handovers/" + foreignHandover.getId()).with(user("department-head").roles("DEPARTMENT_WORKER"))
                .header("X-Tenant-Id", tenant).header("X-Company-Id", companyId)).andExpect(status().isNotFound());
    }

    @Test
    void departmentHeadTracksApprovedRequestWithoutSourcingCommands() throws Exception {
        var request = named(new PurchaseRequest(), "Approved request");
        request.setStatus("APPROVED");
        request.setDepartment(own);
        request = requests.save(request);
        mvc.perform(get("/api/purchase-requests/" + request.getId() + "/workspace")
                .with(user("department-head").roles("DEPARTMENT_HEAD")).header("X-Tenant-Id", tenant).header("X-Company-Id", companyId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.availableActions.primary").isEmpty())
            .andExpect(jsonPath("$.data.availableActions.secondary").isEmpty());
    }

    @Test
    void assetsAndHandoverHistoryStayInsideTheDepartment() throws Exception {
        var ownAsset = asset(own);
        var foreignAsset = asset(other);
        var ownHandover = handover(own);
        var foreignHandover = handover(other);
        mvc.perform(get("/api/fixed-assets").param("size", "1")
                .with(user("department-head").roles("DEPARTMENT_HEAD"))
                .header("X-Tenant-Id", tenant).header("X-Company-Id", companyId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(ownAsset.getId().toString()));
        mvc.perform(get("/api/asset-handovers").param("size", "1")
                .with(user("department-head").roles("DEPARTMENT_HEAD"))
                .header("X-Tenant-Id", tenant).header("X-Company-Id", companyId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(ownHandover.getId().toString()));
        for (String path : List.of("/api/fixed-assets/" + foreignAsset.getId(),
            "/api/fixed-assets/" + foreignAsset.getId() + "/events", "/api/asset-handovers/" + foreignHandover.getId())) {
            mvc.perform(get(path).with(user("department-head").roles("DEPARTMENT_HEAD"))
                    .header("X-Tenant-Id", tenant).header("X-Company-Id", companyId))
                .andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/fixed-assets/" + foreignAsset.getId() + "/mark-idle")
                .with(user("department-head").roles("DEPARTMENT_HEAD"))
                .header("X-Tenant-Id", tenant).header("X-Company-Id", companyId)
                .header("Idempotency-Key", "foreign-asset")
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0,\"reason\":\"Denied\"}"))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/asset-handovers/" + ownHandover.getId() + "/dispatch")
                .with(user("department-head").roles("DEPARTMENT_HEAD"))
                .header("X-Tenant-Id", tenant).header("X-Company-Id", companyId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0,\"reason\":\"Receiving department\"}"))
            .andExpect(status().isForbidden());
        var outgoing = handover(other);
        outgoing.setFromDepartmentId(own.getId());
        outgoing.setHandoverStatus(com.enterprise.mm.enums.AssetHandoverStatus.DISPATCHED);
        handovers.save(outgoing);
        mvc.perform(post("/api/asset-handovers/" + outgoing.getId() + "/acknowledge")
                .with(user("department-head").roles("DEPARTMENT_HEAD"))
                .header("X-Tenant-Id", tenant).header("X-Company-Id", companyId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0,\"reason\":\"Sending department\"}"))
            .andExpect(status().isForbidden());
    }

    private FixedAsset asset(Department department) {
        var asset = named(new FixedAsset(), "Asset");
        asset.setMaterialId(material.getId());
        asset.setDepartmentId(department.getId());
        asset.setCustodyKind(com.enterprise.mm.enums.FixedAssetCustodyKind.DEPARTMENT);
        return assets.save(asset);
    }

    private AssetHandover handover(Department department) {
        var handover = named(new AssetHandover(), "Handover");
        handover.setHandoverType(com.enterprise.mm.enums.AssetHandoverType.ISSUE);
        handover.setHandoverStatus(com.enterprise.mm.enums.AssetHandoverStatus.DRAFT);
        handover.setFromCustodyType(com.enterprise.mm.enums.FixedAssetCustodyKind.WAREHOUSE);
        handover.setFromWarehouseId(warehouse.getId());
        handover.setToCustodyType(com.enterprise.mm.enums.FixedAssetCustodyKind.DEPARTMENT);
        handover.setToDepartmentId(department.getId());
        return handovers.save(handover);
    }

    private DemandPlan plan(Department department) {
        var plan = named(new DemandPlan(), "Plan");
        plan.setDepartment(department);
        plan.setPlanYear(2026);
        plan.setPeriodType("YEAR");
        plan.setPeriodStart(LocalDate.of(2026, 1, 1));
        plan.setPeriodEnd(LocalDate.of(2026, 12, 31));
        return plans.save(plan);
    }

    private MaterialReservation reservation(DemandPlan plan) {
        var reservation = named(new MaterialReservation(), "Reservation");
        reservation.setMaterialId(material.getId());
        reservation.setWarehouseId(warehouse.getId());
        reservation.setDemandPlan(plan);
        reservation.setReservationType("DEMAND");
        reservation.setPriority("NORMAL");
        reservation.setQuantity(BigDecimal.ONE);
        return reservations.save(reservation);
    }

    private IssueRequest issue(Department department) {
        var issue = named(new IssueRequest(), "Issue");
        issue.setWarehouseId(warehouse.getId());
        issue.setRequesterUserId(head.getId());
        issue.setReceiverUserId(head.getId());
        issue.setCostObjectType(IssueRequestCostObjectType.DEPARTMENT);
        issue.setCostObjectId(department.getId());
        return issues.save(issue);
    }

    private MaterialReturnDocument returned(IssueRequest issue) {
        var returned = named(new MaterialReturnDocument(), "Return");
        returned.setStatus("DRAFT");
        returned.setWarehouseId(warehouse.getId());
        returned.setIssueRequestId(issue == null ? null : issue.getId());
        returned.setOriginType(issue == null ? "HISTORICAL_1C" : "NATIVE_ISSUE");
        return returns.save(returned);
    }

    private <T extends BusinessEntity> T named(T entity, String name) {
        entity.setTenantId(tenant);
        entity.setCompanyId(companyId == null ? CompanyContext.DEFAULT_COMPANY_ID : companyId);
        entity.setCode(UUID.randomUUID().toString());
        entity.setName(name);
        entity.setStatus("ACTIVE");
        return entity;
    }
}
