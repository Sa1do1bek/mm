package com.enterprise.mm;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
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
class DepartmentScopedCrudContractTest {
    private static final UUID COMPANY_ID = CompanyContext.DEFAULT_COMPANY_ID;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired MaterialGroupRepository materialGroupRepository;
    @Autowired UnitOfMeasureRepository unitOfMeasureRepository;
    @Autowired MaterialRepository materialRepository;

    private String tenantId;
    private Department ownDepartment;
    private Department otherDepartment;
    private Material material;

    @BeforeEach
    void setUp() {
        tenantId = "dept-crud-" + UUID.randomUUID();
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(COMPANY_ID);
        ownDepartment = department("OWN");
        otherDepartment = department("OTHER");
        employee("department-worker", ownDepartment);
        material = material();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void departmentWorkerCrudIsLimitedToOwnDepartment() throws Exception {
        assertOwnDepartmentCrudOnly(endpoint(
            "/api/purchase-requests",
            "PR",
            (departmentId, name) -> Map.of(
                "code", code("PR"),
                "name", name,
                "departmentId", departmentId.toString(),
                "requestedBy", "request-owner",
                "requestType", "GOODS",
                "requiredDate", LocalDate.now().plusDays(10).toString(),
                "currencyCode", "UZS",
                "totalAmount", 0
            ),
            true
        ));
        assertOwnDepartmentCrudOnly(endpoint(
            "/api/demand-plans",
            "DP",
            (departmentId, name) -> Map.ofEntries(
                Map.entry("code", code("DP")),
                Map.entry("name", name),
                Map.entry("requestType", "GOODS"),
                Map.entry("planYear", 2026),
                Map.entry("periodType", "YEAR"),
                Map.entry("periodStart", "2026-01-01"),
                Map.entry("periodEnd", "2026-12-31"),
                Map.entry("departmentId", departmentId.toString()),
                Map.entry("ownerUsername", "department-worker"),
                Map.entry("currencyCode", "UZS"),
                Map.entry("totalPlannedAmount", 0),
                Map.entry("totalApprovedAmount", 0),
                Map.entry("totalReservedQuantity", 0)
            ),
            true
        ));
        assertReferenceMaintenanceForbidden(endpoint(
            "/api/equipment",
            "EQ",
            (departmentId, name) -> Map.of(
                "code", code("EQ"),
                "name", name,
                "departmentId", departmentId.toString(),
                "status", "ACTIVE",
                "location", "Workshop"
            )
        ));
        assertReferenceMaintenanceForbidden(endpoint(
            "/api/positions",
            "POS",
            (departmentId, name) -> Map.of(
                "code", code("POS"),
                "name", name,
                "departmentId", departmentId.toString(),
                "positionCategory", "STAFF",
                "headcount", 1,
                "occupiedHeadcount", 0
            )
        ));
        assertReferenceMaintenanceForbidden(endpoint(
            "/api/material-consumption-norms",
            "MCN",
            (departmentId, name) -> Map.of(
                "code", code("MCN"),
                "name", name,
                "materialId", material.getId().toString(),
                "departmentId", departmentId.toString(),
                "normType", "PRODUCTION",
                "baseQuantity", 1,
                "outputQuantity", 1,
                "lossPercent", 0,
                "effectiveFrom", "2026-01-01"
            )
        ));
    }

    @Test
    void positionOccupancyIsComputedByEmployeeAssignments() throws Exception {
        String positionPayload = json(Map.of(
            "code", code("POS"),
            "name", "Computed position occupancy",
            "departmentId", ownDepartment.getId().toString(),
            "positionCategory", "STAFF",
            "headcount", 2,
            "occupiedHeadcount", 9
        ));

        String positionResponse = mockMvc.perform(post("/api/positions")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(positionPayload))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.occupiedHeadcount").value(0))
            .andExpect(jsonPath("$.data.availableHeadcount").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
        String positionId = objectMapper.readTree(positionResponse).path("data").path("id").asText();

        mockMvc.perform(post("/api/employees")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                    "code", code("EMP"),
                    "name", "Occupied employee",
                    "personnelNumber", code("PN"),
                    "departmentId", ownDepartment.getId().toString(),
                    "positionId", positionId,
                    "employmentStatus", "ACTIVE"
                ))))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/api/positions/" + positionId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.headcount").value(2))
            .andExpect(jsonPath("$.data.occupiedHeadcount").value(1))
            .andExpect(jsonPath("$.data.availableHeadcount").value(1));
    }

    private void assertOwnDepartmentCrudOnly(Endpoint endpoint) throws Exception {
        String ownId = create(endpoint, ownDepartment.getId(), "Own " + endpoint.label(), "DEPARTMENT_WORKER");

        mockMvc.perform(post(endpoint.path())
                .with(user("department-worker").roles("DEPARTMENT_WORKER"))
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(endpoint.body().apply(otherDepartment.getId(), "Other denied " + endpoint.label()))))
            .andExpect(status().is4xxClientError());
        mockMvc.perform(post(endpoint.path())
                .with(user("procurement-specialist").roles("PROCUREMENT_SPECIALIST"))
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(endpoint.body().apply(otherDepartment.getId(), "Procurement other denied " + endpoint.label()))))
            .andExpect(status().is4xxClientError());

        String otherId = create(endpoint, otherDepartment.getId(), "Other " + endpoint.label(), "ADMIN");

        mockMvc.perform(get(endpoint.path())
                .with(user("department-worker").roles("DEPARTMENT_WORKER"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[*].id", everyItem(not(otherId))));

        mockMvc.perform(get(endpoint.path() + "/" + otherId)
                .with(user("department-worker").roles("DEPARTMENT_WORKER"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isNotFound());

        mockMvc.perform(put(endpoint.path() + "/" + otherId)
                .with(user("department-worker").roles("DEPARTMENT_WORKER"))
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(endpoint.body().apply(otherDepartment.getId(), "Other update denied " + endpoint.label()))))
            .andExpect(status().isNotFound());

        mockMvc.perform(delete(endpoint.path() + "/" + otherId)
                .with(user("department-worker").roles("DEPARTMENT_WORKER"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isNotFound());

        mockMvc.perform(put(endpoint.path() + "/" + ownId)
                .with(user("department-worker").roles("DEPARTMENT_WORKER"))
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(endpoint.body().apply(ownDepartment.getId(), "Own updated " + endpoint.label()))))
            .andExpect(endpoint.ownMutationAllowed() ? status().isOk() : status().is4xxClientError());

        mockMvc.perform(delete(endpoint.path() + "/" + ownId)
                .with(user("department-worker").roles("DEPARTMENT_WORKER"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(endpoint.ownMutationAllowed() ? status().isOk() : status().is4xxClientError());

        if (endpoint.ownMutationAllowed()) {
            mockMvc.perform(post(endpoint.path() + "/" + ownId + "/restore")
                    .with(user("department-worker").roles("DEPARTMENT_WORKER"))
                    .header("X-Tenant-Id", tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(ownId));
        }
    }

    private void assertReferenceMaintenanceForbidden(Endpoint endpoint) throws Exception {
        for (String role : List.of("DEPARTMENT_HEAD", "DEPARTMENT_WORKER")) {
            mockMvc.perform(post(endpoint.path()).with(user("department-worker").roles(role))
                    .header("X-Tenant-Id", tenantId).contentType(MediaType.APPLICATION_JSON)
                    .content(json(endpoint.body().apply(ownDepartment.getId(), "Forbidden maintenance"))))
                .andExpect(status().isForbidden());
            String path = endpoint.path() + "/" + UUID.randomUUID();
            mockMvc.perform(put(path).with(user("department-worker").roles(role))
                    .header("X-Tenant-Id", tenantId).contentType(MediaType.APPLICATION_JSON)
                    .content(json(endpoint.body().apply(ownDepartment.getId(), "Forbidden update"))))
                .andExpect(status().isForbidden());
            mockMvc.perform(delete(path).with(user("department-worker").roles(role))
                    .header("X-Tenant-Id", tenantId)).andExpect(status().isForbidden());
            mockMvc.perform(post(path + "/restore").with(user("department-worker").roles(role))
                    .header("X-Tenant-Id", tenantId)).andExpect(status().isForbidden());
        }
    }

    private String create(Endpoint endpoint, UUID departmentId, String name, String role) throws Exception {
        String response = mockMvc.perform(post(endpoint.path())
                .with(user(role.toLowerCase().replace('_', '-')).roles(role))
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(endpoint.body().apply(departmentId, name))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.id").isString())
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode data = objectMapper.readTree(response).path("data");
        return data.path("id").asText();
    }

    private Endpoint endpoint(String path, String label, BiFunction<UUID, String, Map<String, Object>> body) {
        return endpoint(path, label, body, true);
    }

    private Endpoint endpoint(String path, String label, BiFunction<UUID, String, Map<String, Object>> body, boolean ownMutationAllowed) {
        return new Endpoint(path, label, body, ownMutationAllowed);
    }

    private Department department(String label) {
        Department department = new Department();
        department.setCode(code("DEP-" + label));
        department.setName(label + " department");
        department.setStatus("ACTIVE");
        department.setDepartmentType("OPERATIONS");
        return departmentRepository.save(department);
    }

    private Employee employee(String username, Department department) {
        Employee employee = new Employee();
        employee.setCode(code("EMP"));
        employee.setName(username);
        employee.setStatus("ACTIVE");
        employee.setPersonnelNumber(username);
        employee.setDepartment(department);
        return employeeRepository.save(employee);
    }

    private Material material() {
        MaterialGroup group = new MaterialGroup();
        group.setCode(code("MG"));
        group.setName("Contract group");
        group.setStatus("ACTIVE");
        group = materialGroupRepository.save(group);

        UnitOfMeasure unit = new UnitOfMeasure();
        unit.setCode(code("UOM"));
        unit.setName("Piece");
        unit.setStatus("ACTIVE");
        unit.setMeasurementNameShort("pc");
        unit.setMeasurementNameLong("Piece");
        unit = unitOfMeasureRepository.save(unit);

        Material saved = new Material();
        saved.setCode(code("MAT"));
        saved.setName("Contract material");
        saved.setStatus("ACTIVE");
        saved.setMaterialGroupId(group.getId());
        saved.setUnitOfMeasureId(unit.getId());
        return materialRepository.save(saved);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String code(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private record Endpoint(
        String path,
        String label,
        BiFunction<UUID, String, Map<String, Object>> body,
        boolean ownMutationAllowed
    ) {
    }
}
