package com.enterprise.mm;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.CommissionType;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.security.JwtService;
import com.enterprise.mm.warehouse.reports.export.WarehouseExportJobService;
import com.enterprise.mm.warehouse.transfergroup.WarehouseTransferGroupService;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"app.seed.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:warehouse-reference-selectors;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WarehouseReferenceSelectorHttpTest {
    private static final String BASE = "/api/workspaces/warehouse-execution/selectors";
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired EmployeeRepository employees;
    @Autowired WarehouseRepository warehouses;
    @Autowired SupplierRepository suppliers;
    @Autowired CommissionRepository commissions;
    @Autowired CommissionMemberRepository members;
    @Autowired JwtService jwt;
    @MockBean WarehouseExportJobService exportJobs;
    @MockBean WarehouseTransferGroupService transferGroups;
    private UUID companyId;
    private UUID otherCompanyId;
    private Warehouse warehouse;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TenantContext.DEFAULT_TENANT);
        companyId = company("Company A");
        otherCompanyId = company("Company B");
        CompanyContext.setCompany(companyId);
        warehouse = new Warehouse();
        warehouse.setName("Assigned warehouse");
        warehouse.setCode("WH-" + UUID.randomUUID());
        warehouse.setStatus("ACTIVE");
        warehouse.setCompanyId(companyId);
        warehouses.saveAndFlush(warehouse);
    }

    @AfterEach void clearContexts() { TenantContext.clear(); CompanyContext.clear(); }

    @ParameterizedTest
    @ValueSource(strings = {"WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "WAREHOUSE_OPERATOR", "WAREHOUSE_KEEPER"})
    void warehouseRolesGetCompanySupplierIdentitiesWithoutSupplierDetails(String role) throws Exception {
        User actor = actor(role, true);
        Supplier visible = supplier("Visible vendor", companyId);
        Supplier otherCompany = supplier("Other company vendor", otherCompanyId);
        Supplier otherTenant = new Supplier(); otherTenant.setName("Other tenant vendor");
        otherTenant.setCode("S-" + UUID.randomUUID()); otherTenant.setTenantId("other-tenant");
        otherTenant.setCompanyId(companyId); otherTenant.setStatus("ACTIVE"); suppliers.saveAndFlush(otherTenant);
        String token = bearer(actor, role);

        mvc.perform(get(BASE + "/suppliers").header("Authorization", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content", hasSize(1)))
            .andExpect(jsonPath("$.data.content[0].id").value(visible.getId().toString()))
            .andExpect(jsonPath("$.data.content[0].name").value("Visible vendor"))
            .andExpect(jsonPath("$.data.content[0].bankAccount").doesNotExist())
            .andExpect(jsonPath("$.data.content[0].taxId").doesNotExist());
        mvc.perform(get(BASE + "/suppliers").param("search", "Visible").header("Authorization", token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get(BASE + "/suppliers/" + visible.getId()).header("Authorization", token))
            .andExpect(status().isOk());
        mvc.perform(get(BASE + "/suppliers/" + otherCompany.getId()).header("Authorization", token))
            .andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/suppliers/" + otherTenant.getId()).header("Authorization", token))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/suppliers").header("Authorization", token)).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAREHOUSE_OPERATOR", "WAREHOUSE_KEEPER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD"})
    void commissionsRequireActiveMembershipAndCompanyScope(String role) throws Exception {
        User actor = actor(role, true);
        Commission allowed = commission("Own inventory", companyId, CommissionType.INVENTORY, true, null);
        member(allowed, actor, true);
        Commission inactiveMembership = commission("Inactive member", companyId, CommissionType.INVENTORY, true, null);
        member(inactiveMembership, actor, false);
        Commission expired = commission("Expired", companyId, CommissionType.INVENTORY, true, LocalDate.now().minusDays(1));
        member(expired, actor, true);
        Commission inactive = commission("Inactive commission", companyId, CommissionType.INVENTORY, false, null);
        member(inactive, actor, true);
        Commission wrongType = commission("Tender", companyId, CommissionType.TENDER, true, null);
        member(wrongType, actor, true);
        Commission otherCompany = commission("Other company", otherCompanyId, CommissionType.INVENTORY, true, null);
        member(otherCompany, actor, true);
        Commission noMember = commission("Not a member", companyId, CommissionType.INVENTORY, true, null);
        String token = bearer(actor, role);

        mvc.perform(get(BASE + "/inventory-commissions").header("Authorization", token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.content", hasSize(1)))
            .andExpect(jsonPath("$.data.content[0].id").value(allowed.getId().toString()))
            .andExpect(jsonPath("$.data.content[0].name").value(containsString("Own")))
            .andExpect(jsonPath("$.data.content[0].members").doesNotExist());
        mvc.perform(get(BASE + "/inventory-commissions/" + noMember.getId()).header("Authorization", token))
            .andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/inventory-commissions/" + allowed.getId()).header("Authorization", token))
            .andExpect(status().isOk());
    }

    @Test
    void rejectsUnassignedWarehouseUsersAndUnrelatedRoles() throws Exception {
        User unassigned = actor("WAREHOUSE_KEEPER", false);
        String token = bearer(unassigned, "WAREHOUSE_KEEPER");
        mvc.perform(get(BASE + "/suppliers").header("Authorization", token)).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/inventory-commissions").header("Authorization", token)).andExpect(status().isForbidden());
        User department = actor("DEPARTMENT_HEAD", false);
        mvc.perform(get(BASE + "/suppliers").header("Authorization", bearer(department, "DEPARTMENT_HEAD")))
            .andExpect(status().isForbidden());

        Warehouse foreign = new Warehouse(); foreign.setName("Foreign warehouse"); foreign.setCode("W-" + UUID.randomUUID());
        foreign.setCompanyId(otherCompanyId); foreign.setStatus("ACTIVE"); warehouses.saveAndFlush(foreign);
        User mismatch = actor("WAREHOUSE_KEEPER", true);
        mismatch.getEmployee().setWarehouse(foreign); employees.saveAndFlush(mismatch.getEmployee());
        mvc.perform(get(BASE + "/suppliers").header("Authorization", bearer(mismatch, "WAREHOUSE_KEEPER")))
            .andExpect(status().isForbidden());
    }

    @Test
    void anExistingInactiveAssignedWarehouseDoesNotInvalidateLookupAccess() throws Exception {
        warehouse.setStatus("INACTIVE"); warehouses.saveAndFlush(warehouse);
        User actor = actor("WAREHOUSE_KEEPER", true);
        mvc.perform(get(BASE + "/suppliers").header("Authorization", bearer(actor, "WAREHOUSE_KEEPER")))
            .andExpect(status().isOk());
    }

    private UUID company(String name) {
        Company c = new Company(); c.setName(name); c.setCode("C-" + UUID.randomUUID());
        return companies.saveAndFlush(c).getId();
    }
    private User actor(String role, boolean assigned) {
        User u = new User(); u.setName("Warehouse user"); u.setUsername("audit-" + UUID.randomUUID());
        u.setCode(u.getUsername()); u.setTenantId(TenantContext.DEFAULT_TENANT); u.setCompanyId(companyId);
        u.setEnabled(true); u.setPasswordHash("test-hash"); u.setRoles(new HashSet<>(Set.of(RoleName.valueOf(role))));
        if (assigned) {
            Employee e = new Employee(); e.setName("Warehouse employee"); e.setCode("E-" + UUID.randomUUID());
            e.setCompanyId(companyId); e.setWarehouse(warehouse); employees.saveAndFlush(e); u.setEmployee(e);
        }
        return users.saveAndFlush(u);
    }
    private String bearer(User user, String role) {
        return "Bearer " + jwt.generate(user, List.of(new SimpleGrantedAuthority("ROLE_" + role)), user.getTenantId(), companyId);
    }
    private Supplier supplier(String name, UUID owner) {
        Supplier s = new Supplier(); s.setName(name); s.setCode("S-" + UUID.randomUUID());
        s.setCompanyId(owner); s.setStatus("ACTIVE"); return suppliers.saveAndFlush(s);
    }
    private Commission commission(String name, UUID owner, CommissionType type, boolean active, LocalDate validTo) {
        Commission c = new Commission(); c.setName(name); c.setCode("C-" + UUID.randomUUID());
        c.setCompanyId(owner); c.setType(type); c.setActive(active); c.setValidTo(validTo); c.setStatus("ACTIVE");
        return commissions.saveAndFlush(c);
    }
    private void member(Commission c, User user, boolean active) {
        CommissionMember m = new CommissionMember(); m.setName("Member"); m.setMemberName("Member");
        m.setCode("M-" + UUID.randomUUID()); m.setCompanyId(c.getCompanyId()); m.setCommissionId(c.getId());
        m.setUserId(user.getId()); m.setActive(active); members.saveAndFlush(m);
    }
}
