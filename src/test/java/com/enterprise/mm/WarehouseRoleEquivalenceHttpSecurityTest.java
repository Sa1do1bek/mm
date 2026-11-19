package com.enterprise.mm;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.DepartmentWarehouseAccess;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.DepartmentWarehouseAccessRepository;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import org.junit.jupiter.api.Test;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.security.JwtService;
import com.enterprise.mm.warehouse.reports.export.WarehouseExportJobService;
import com.enterprise.mm.warehouse.transfergroup.WarehouseTransferGroupService;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"app.seed.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:warehouse-role-equivalence;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"})
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
@Transactional
class WarehouseRoleEquivalenceHttpSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired EmployeeRepository employees;
    @Autowired WarehouseRepository warehouses;
    @Autowired JwtService jwt;
    @Autowired DepartmentRepository departments;
    @Autowired com.enterprise.mm.repository.IssueRequestRepository issueRequests;
    @Autowired com.enterprise.mm.repository.MaterialReturnDocumentRepository returns;
    @Autowired DepartmentWarehouseAccessRepository departmentAccess;
    @MockBean WarehouseExportJobService exportJobs;
    @MockBean WarehouseTransferGroupService transferGroups;
    private UUID companyId;
    private User actor;

    @BeforeEach
    void createCompany() {
        TenantContext.setTenant(TenantContext.DEFAULT_TENANT);
        Company company = new Company();
        company.setName("Warehouse permissions company");
        company.setCode("WH-PERM-" + UUID.randomUUID());
        companyId = companies.saveAndFlush(company).getId();
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "DEPARTMENT_HEAD"})
    void bothLeadershipNamesPassManagerOnlyMethodSecurity(String role) throws Exception {
        mvc.perform(get("/api/warehouse-reports/export-jobs").header("Authorization", bearer(role)))
            .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAREHOUSE_OPERATOR", "WAREHOUSE_KEEPER", "VIEWER"})
    void operationalAndReadOnlyRolesCannotUseLeadershipOnlyExportJobs(String role) throws Exception {
        mvc.perform(get("/api/warehouse-reports/export-jobs").header("Authorization", bearer(role)))
            .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAREHOUSE_OPERATOR", "WAREHOUSE_KEEPER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "DEPARTMENT_HEAD"})
    void bothOperationalNamesPassOperatorOnlyMethodSecurity(String role) throws Exception {
        mvc.perform(post("/api/v1/warehouse/transfer-groups").header("Authorization", bearer(role))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "WAREHOUSE_OPERATOR", "WAREHOUSE_KEEPER", "DEPARTMENT_HEAD"})
    void equivalentRolesRemainWarehouseScopedAndCannotAdministerUsersOrWarehouseMasters(String role) throws Exception {
        String token = bearer(role);
        mvc.perform(get("/api/stock/balances").header("Authorization", token))
            .andExpect(status().isForbidden()); // No assigned employee/warehouse.
        mvc.perform(get("/api/users").header("Authorization", token))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/warehouses").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/inventory-finance/period-closes/" + UUID.randomUUID() + "/reopen")
                .header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "WAREHOUSE_OPERATOR", "WAREHOUSE_KEEPER"})
    void equivalentRolesCanReadOnlyTheirAssignedWarehouse(String role) throws Exception {
        String token = bearer(role);
        Warehouse warehouse = new Warehouse();
        warehouse.setName("Assigned warehouse");
        warehouse.setCode("WH-" + UUID.randomUUID());
        warehouse.setStatus("ACTIVE");
        warehouse.setCompanyId(companyId);
        warehouses.saveAndFlush(warehouse);
        Employee employee = new Employee();
        employee.setName("Assigned warehouse employee");
        employee.setCode("EMP-" + UUID.randomUUID());
        employee.setCompanyId(companyId);
        employee.setWarehouse(warehouse);
        employees.saveAndFlush(employee);
        actor.setEmployee(employee);
        users.saveAndFlush(actor);

        mvc.perform(get("/api/stock/balances").param("warehouseId", warehouse.getId().toString())
                .header("Authorization", token))
            .andExpect(status().isOk());
        mvc.perform(get("/api/stock/balances").param("warehouseId", UUID.randomUUID().toString())
                .header("Authorization", token))
            .andExpect(status().isForbidden());
    }

    @Test
    void departmentHeadManagesEveryDepartmentWarehouseAndCannotSelectAForeignWarehouse() throws Exception {
        String token = bearer("DEPARTMENT_HEAD");
        Department own = department("Own department");
        Department other = department("Other department");
        Warehouse first = warehouse("First");
        Warehouse second = warehouse("Second");
        Warehouse foreign = warehouse("Foreign");
        Warehouse inactive = warehouse("Inactive");
        inactive.setStatus("INACTIVE");
        warehouses.saveAndFlush(inactive);
        Warehouse deleted = warehouse("Deleted"); deleted.softDelete(); warehouses.saveAndFlush(deleted);
        Company anotherCompany = new Company(); anotherCompany.setName("Another company"); anotherCompany.setCode("CO-" + UUID.randomUUID());
        companies.saveAndFlush(anotherCompany);
        Warehouse outsideCompany = warehouse("Other company warehouse"); outsideCompany.setCompanyId(anotherCompany.getId()); warehouses.saveAndFlush(outsideCompany);
        Warehouse outsideTenant = warehouse("Other tenant warehouse"); outsideTenant.setTenantId("other-tenant"); warehouses.saveAndFlush(outsideTenant);
        grant(own, first); grant(own, second); grant(other, foreign); grant(own, inactive);
        // Deliberately malformed historical grants must not bypass the warehouse's own tenant/company.
        grant(own, deleted); grant(own, outsideCompany); grant(own, outsideTenant);
        actor.setDepartment(own);
        Employee employee = new Employee();
        employee.setName("Head with a personal warehouse"); employee.setCode("EMP-" + UUID.randomUUID());
        employee.setCompanyId(companyId); employee.setDepartment(own); employee.setWarehouse(foreign);
        employees.saveAndFlush(employee);
        actor.setEmployee(employee);
        users.saveAndFlush(actor);

        var firstIssue = issue(first, other);
        issue(second, other);
        var foreignIssue = issue(foreign, own);
        returned(firstIssue);
        returned(foreignIssue);
        mvc.perform(get("/api/issue-requests").header("Authorization", token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2));
        mvc.perform(get("/api/issue-requests/" + firstIssue.getId()).header("Authorization", token))
            .andExpect(status().isOk());
        mvc.perform(get("/api/issue-requests/" + foreignIssue.getId()).header("Authorization", token))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/returns").header("Authorization", token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));

        mvc.perform(get("/api/auth/me").header("Authorization", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.isWarehouseRestricted").value(true))
            .andExpect(jsonPath("$.data.assignedWarehouseId").isEmpty())
            .andExpect(jsonPath("$.data.accessibleWarehouseIds.length()").value(2));
        mvc.perform(get("/api/warehouses").header("Authorization", token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2));
        for (Warehouse allowed : List.of(first, second)) {
            mvc.perform(get("/api/stock/balances").param("warehouseId", allowed.getId().toString())
                    .header("Authorization", token)).andExpect(status().isOk());
            mvc.perform(get("/api/workspaces/warehouse-execution/manual/fixed-asset-stock/context")
                    .param("warehouseId", allowed.getId().toString()).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.warehouseId").value(allowed.getId().toString()));
        }
        for (Warehouse denied : List.of(foreign, inactive, deleted, outsideCompany, outsideTenant)) {
            mvc.perform(get("/api/stock/balances").param("warehouseId", denied.getId().toString())
                    .header("Authorization", token)).andExpect(status().isForbidden());
            mvc.perform(get("/api/workspaces/warehouse-execution/manual/fixed-asset-stock/context")
                    .param("warehouseId", denied.getId().toString()).header("Authorization", token))
                .andExpect(status().isForbidden());
        }
        departmentAccess.deleteAll(); departmentAccess.flush();
        mvc.perform(get("/api/auth/me").header("Authorization", token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.accessibleWarehouseIds").isEmpty());
        mvc.perform(get("/api/stock/balances").param("warehouseId", first.getId().toString())
                .header("Authorization", token)).andExpect(status().isForbidden());
    }

    private com.enterprise.mm.entity.IssueRequest issue(Warehouse warehouse, Department department) {
        var issue = new com.enterprise.mm.entity.IssueRequest();
        issue.setName("Department issue"); issue.setCode("IR-" + UUID.randomUUID());
        issue.setCompanyId(companyId); issue.setStatus("DRAFT");
        issue.setWarehouseId(warehouse.getId()); issue.setRequesterUserId(actor.getId()); issue.setReceiverUserId(actor.getId());
        issue.setCostObjectType(com.enterprise.mm.enums.IssueRequestCostObjectType.DEPARTMENT);
        issue.setCostObjectId(department.getId());
        return issueRequests.saveAndFlush(issue);
    }

    private void returned(com.enterprise.mm.entity.IssueRequest issue) {
        var returned = new com.enterprise.mm.entity.MaterialReturnDocument();
        returned.setName("Department return"); returned.setCode("RET-" + UUID.randomUUID());
        returned.setCompanyId(companyId); returned.setStatus("DRAFT");
        returned.setWarehouseId(issue.getWarehouseId()); returned.setIssueRequestId(issue.getId()); returned.setOriginType("NATIVE_ISSUE");
        returns.saveAndFlush(returned);
    }

    private Department department(String name) {
        Department department = new Department();
        department.setName(name); department.setCode("DEP-" + UUID.randomUUID());
        department.setCompanyId(companyId); department.setStatus("ACTIVE");
        return departments.saveAndFlush(department);
    }

    private Warehouse warehouse(String name) {
        Warehouse warehouse = new Warehouse();
        warehouse.setName(name); warehouse.setCode("WH-" + UUID.randomUUID());
        warehouse.setCompanyId(companyId); warehouse.setStatus("ACTIVE");
        return warehouses.saveAndFlush(warehouse);
    }

    private void grant(Department department, Warehouse warehouse) {
        DepartmentWarehouseAccess access = new DepartmentWarehouseAccess();
        access.setTenantId(TenantContext.currentTenant()); access.setCompanyId(companyId);
        access.setDepartment(department); access.setWarehouse(warehouse);
        departmentAccess.saveAndFlush(access);
    }

    private String bearer(String role) {
        User user = new User();
        user.setUsername("warehouse-equivalence-" + UUID.randomUUID());
        user.setName("Warehouse role test");
        user.setCode(user.getUsername());
        user.setTenantId(TenantContext.DEFAULT_TENANT);
        user.setCompanyId(companyId);
        user.setEnabled(true);
        user.setPasswordHash("test-password-hash");
        user.setRoles(new HashSet<>(Set.of(RoleName.valueOf(role))));
        users.saveAndFlush(user);
        actor = user;
        // Deliberately issue a single-role token as older deployed versions did.
        return "Bearer " + jwt.generate(user, List.of(new SimpleGrantedAuthority("ROLE_" + role)),
            user.getTenantId(), user.getCompanyId());
    }
}
