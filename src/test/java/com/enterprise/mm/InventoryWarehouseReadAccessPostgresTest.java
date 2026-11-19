package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.seed.enabled=false", "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
    "app.firebase.enabled=false", "mm.integrations.cbu.enabled=false", "app.scheduling.enabled=false"
})
@EntityScan(basePackages={"com.enterprise.mm.entity", "com.enterprise.mm.procurement.command.entity",
    "com.enterprise.mm.supply.workspace.entity", "com.enterprise.mm.common", "com.enterprise.mm.warehouse"})
@ActiveProfiles("test")
@Testcontainers
class InventoryWarehouseReadAccessPostgresTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
        r.add("spring.datasource.driver-class-name", DB::getDriverClassName);
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired CompanyRepository companies;
    @Autowired DepartmentRepository departments;
    @Autowired DepartmentWarehouseAccessRepository grants;
    @Autowired WarehouseRepository warehouses;
    @Autowired EmployeeRepository employees;
    @Autowired UserRepository users;
    @Autowired CommissionRepository commissions;
    @Autowired CommissionMemberRepository members;
    @Autowired MaterialRepository materials;
    @Autowired UnitOfMeasureRepository units;
    @Autowired InventoryDocumentRepository documents;
    @Autowired InventoryItemRepository items;
    UUID companyId;
    Department department;
    Warehouse first, second, outside;
    Commission commission;
    InventoryDocument planned, counting, otherDepartment, global;
    InventoryItem line;
    User actor;
    String token;

    private <T extends BusinessEntity> T named(T entity, String code, String status) {
        entity.setTenantId(TenantContext.DEFAULT_TENANT); entity.setCompanyId(companyId);
        entity.setCode(code); entity.setName(code); entity.setStatus(status); return entity;
    }

    @BeforeEach void seed() {
        TenantContext.setTenant(TenantContext.DEFAULT_TENANT);
        companyId = companies.saveAndFlush(named(new Company(), "INVENTORY-READ-"+UUID.randomUUID(), "ACTIVE")).getId();
        CompanyContext.setCompany(companyId);
        department = departments.saveAndFlush(named(new Department(), "DEPARTMENT", "ACTIVE"));
        first = warehouses.saveAndFlush(named(new Warehouse(), "OWN-1", "ACTIVE"));
        second = warehouses.saveAndFlush(named(new Warehouse(), "OWN-2", "ACTIVE"));
        outside = warehouses.saveAndFlush(named(new Warehouse(), "OUTSIDE", "ACTIVE"));
        for (Warehouse warehouse : List.of(first, second)) {
            var grant = new DepartmentWarehouseAccess(); grant.setTenantId(TenantContext.DEFAULT_TENANT);
            grant.setCompanyId(companyId); grant.setDepartment(department); grant.setWarehouse(warehouse); grants.saveAndFlush(grant);
        }
        commission = named(new Commission(), "COMMISSION", "ACTIVE");
        commission.setType(CommissionType.INVENTORY); commission = commissions.saveAndFlush(commission);
        planned = document("PLANNED-OWN", first, "PLANNED");
        counting = document("COUNTING-OWN", first, "IN_PROGRESS");
        counting.setSnapshotAt(LocalDateTime.now()); counting = documents.saveAndFlush(counting);
        document("SECOND-WAREHOUSE", second, "PLANNED");
        otherDepartment = document("OTHER-DEPARTMENT", outside, "PLANNED");
        global = document("COMPANY-WIDE", null, "PLANNED");
        var unit = units.saveAndFlush(named(new UnitOfMeasure(), "pcs", "ACTIVE"));
        var material = named(new Material(), "BEARING", "ACTIVE"); material.setUnitOfMeasureId(unit.getId());
        material = materials.saveAndFlush(material);
        line = named(new InventoryItem(), "COUNT-LINE", "DISCREPANCY");
        line.setInventoryDocumentId(counting.getId()); line.setMaterialId(material.getId()); line.setWarehouseId(first.getId());
        line.setSystemQuantity(BigDecimal.TEN); line.setCountedQuantity(new BigDecimal("9"));
        line.setDifferenceQuantity(BigDecimal.ONE.negate()); line.setLineStatus(InventoryLineStatus.DISCREPANCY);
        line = items.saveAndFlush(line);
        var globalLine = named(new InventoryItem(), "GLOBAL-LINE", "NOT_COUNTED");
        globalLine.setInventoryDocumentId(global.getId()); globalLine.setMaterialId(material.getId());
        globalLine.setWarehouseId(first.getId()); items.saveAndFlush(globalLine);
    }

    @AfterEach void clearContext() { TenantContext.clear(); CompanyContext.clear(); }

    private InventoryDocument document(String name, Warehouse warehouse, String status) {
        var document = named(new InventoryDocument(), name, status);
        document.setCommissionId(commission.getId()); document.setGlobalWarehouse(warehouse == null);
        document.setWarehouseId(warehouse == null ? null : warehouse.getId()); return documents.saveAndFlush(document);
    }

    private void login(RoleName role, boolean assigned) {
        var employee = named(new Employee(), "EMP-"+role, "ACTIVE"); employee.setDepartment(department);
        if (assigned) employee.setWarehouse(first); employee = employees.saveAndFlush(employee);
        actor = named(new User(), "USER-"+role, "ACTIVE"); actor.setUsername("inventory-"+role);
        actor.setPasswordHash("not-a-login-password"); actor.setEnabled(true); actor.setRoles(Set.of(role));
        actor.setEmployee(employee); actor.setDepartment(department); actor = users.saveAndFlush(actor);
        token = jwt.generate(actor, List.of(new SimpleGrantedAuthority("ROLE_"+role)));
    }

    private JsonNode request(String method, String path, Object body, int expected) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer "+token).header("X-Tenant-Id", TenantContext.DEFAULT_TENANT)
            .header("X-Company-Id", companyId.toString()).header("Content-Type", "application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString());
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s: %s",method,path,response.body()).isEqualTo(expected);
        return json.readTree(response.body()).path("data");
    }

    @ParameterizedTest
    @EnumSource(value=RoleName.class, names={"WAREHOUSE_HEAD", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_KEEPER", "DEPARTMENT_HEAD"})
    void warehouseAuthorityGrantsReadOnlyAccessWithoutCommissionMembership(RoleName role) throws Exception {
        login(role, true);
        var registry = request("GET", "/api/inventory", null, 200);
        assertThat(registry.path("totalElements").asInt()).isEqualTo(role == RoleName.DEPARTMENT_HEAD ? 3 : 2);
        assertThat(registry.toString()).contains(planned.getId().toString(), counting.getId().toString())
            .doesNotContain(otherDepartment.getId().toString(), global.getId().toString());
        request("GET", "/api/inventory/"+planned.getId(), null, 200);
        var workspace = request("GET", "/api/inventory/"+counting.getId()+"/workspace", null, 200);
        var capabilities = workspace.path("capabilities");
        assertThat(capabilities.path("canSeeSystemQuantity").asBoolean()).isTrue();
        for (String action : List.of("canCount", "canReview", "canApprove", "canPost", "canPlan", "canDecide", "canReturnForCorrection", "canValueFoundStock"))
            assertThat(capabilities.path(action).asBoolean()).as(action).isFalse();
        assertThat(new BigDecimal(workspace.path("lines").get(0).path("systemQuantity").asText())).isEqualByComparingTo("10");
        assertThat(new BigDecimal(workspace.path("lines").get(0).path("differenceQuantity").asText())).isEqualByComparingTo("-1");
        assertThat(request("GET", "/api/inventory-items", null, 200).path("totalElements").asInt()).isEqualTo(1);
        request("GET", "/api/inventory/"+otherDepartment.getId(), null, 403);
        request("GET", "/api/inventory/"+global.getId(), null, 403);
        request("PUT", "/api/inventory/"+planned.getId(), Map.of("name","Changed"), 403);
        request("DELETE", "/api/inventory/"+planned.getId(), null, 403);
        request("POST", "/api/inventory/"+planned.getId()+"/restore", Map.of(), 403);
        for (String action : List.of("start", "cancel", "close", "submit-approval", "complete-counting"))
            request("POST", "/api/inventory/"+planned.getId()+"/"+action, Map.of(), 403);
        request("POST", "/api/inventory/"+counting.getId()+"/counts",
            Map.of("expectedDocumentVersion", counting.getVersion(), "rows", List.of(Map.of("lineId",line.getId(),"expectedLineVersion",line.getVersion(),"countedQuantity",8))),403);
        request("PUT", "/api/inventory-items/"+line.getId(), Map.of("commentText","Changed"),403);
        request("DELETE", "/api/inventory-items/"+line.getId(),null,403);
        assertThat(documents.findById(planned.getId()).orElseThrow().getStatus()).isEqualTo("PLANNED");
        assertThat(items.findById(line.getId()).orElseThrow().getCountedQuantity()).isEqualByComparingTo("9");
    }

    @Test void commissionCountersRetainBlindCountingAfterWarehouseReadIsGranted() throws Exception {
        login(RoleName.WAREHOUSE_HEAD, true);
        var member = named(new CommissionMember(), "COUNTER", "ACTIVE"); member.setCommissionId(commission.getId());
        member.setUserId(actor.getId()); member.setMemberName(actor.getName()); member.setMemberRole(CommissionMemberRole.COUNTER);
        members.saveAndFlush(member);
        var workspace = request("GET", "/api/inventory/"+counting.getId()+"/workspace", null,200);
        assertThat(workspace.path("capabilities").path("canCount").asBoolean()).isTrue();
        assertThat(workspace.path("capabilities").path("canSeeSystemQuantity").asBoolean()).isFalse();
        assertThat(workspace.path("lines").get(0).hasNonNull("systemQuantity")).isFalse();
    }

    @Test void departmentWorkerDoesNotAcquireWarehouseInventoryAccess() throws Exception {
        login(RoleName.DEPARTMENT_WORKER, true);
        assertThat(request("GET", "/api/inventory", null,200).path("totalElements").asInt()).isZero();
        request("GET", "/api/inventory/"+planned.getId(),null,403);
    }
}
