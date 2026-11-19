package com.enterprise.mm;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.enterprise.mm.service.TotpService;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.config.OpenApiConfig;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.Role;
import com.enterprise.mm.entity.StockBatch;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RoleRepository;
import com.enterprise.mm.repository.StockBatchRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import io.swagger.v3.oas.models.OpenAPI;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Iterator;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class SecurityAndReportsTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RoleRepository roleRepository;
    @Autowired UserRepository userRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired MaterialGroupRepository materialGroupRepository;
    @Autowired UnitOfMeasureRepository unitOfMeasureRepository;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired MaterialRepository materialRepository;
    @Autowired PurchaseRequestRepository purchaseRequestRepository;
    @Autowired StockBatchRepository stockBatchRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TotpService totpService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired OpenAPI openAPI;

    @BeforeEach
    void resetTenantAndCompanyContext() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
    }

    @Test
    void auditorOnlyEndpointRejectsWarehouseRole() throws Exception {
        mockMvc.perform(get("/api/audit").with(user("warehouse").roles("WAREHOUSE_OPERATOR")))
            .andExpect(status().isForbidden())
            .andExpect(content().string(containsString("Access denied")));
    }

    @Test
    void unauthenticatedApiRequestReturnsJsonEnvelope() throws Exception {
        mockMvc.perform(get("/api/materials"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().string(containsString("Authentication required")));
    }

    @Test
    void invalidTenantHeaderReturnsJsonEnvelope() throws Exception {
        mockMvc.perform(get("/api/materials")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "bad tenant!"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().string(containsString("Invalid tenant id")));
    }

    @Test
    void auditLogCannotBeMutatedThroughCrudApi() throws Exception {
        mockMvc.perform(post("/api/audit")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code":"AUDIT-TAMPER","name":"Tamper","action":"DELETE"}
                    """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().string(containsString("Audit log is immutable")));
    }

    @Test
    void auditApiShowsRecordedChanges() throws Exception {
        String code = "uom" + System.nanoTime();
        mockMvc.perform(post("/api/uom")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code":"%s","measurementNameShort":"%s","measurementNameLong":"Audit UOM","unitType":"MAIN"}
                    """.formatted(code, code)))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/api/audit")
                .with(user("admin").roles("ADMIN"))
                .param("search", code))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("UnitOfMeasure CREATE")))
            .andExpect(content().string(containsString(code)));
    }

    @Test
    void unitOfMeasureApiPersistsExtendedFields() throws Exception {
        String code = "kg-" + System.nanoTime();
        String longName = "Kilogram " + code;
        mockMvc.perform(post("/api/uom")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code":"%s",
                      "measurementNameShort":"%s",
                      "measurementNameLong":"%s",
                      "unitType":"MAIN"
                    }
                    """.formatted(code, code, longName)))
            .andExpect(status().isCreated())
            .andExpect(content().string(containsString("\"measurementNameShort\":\"" + code + "\"")))
            .andExpect(content().string(containsString("\"measurementNameLong\":\"" + longName + "\"")))
            .andExpect(content().string(containsString("\"unitType\":\"MAIN\"")));
    }

    @Test
    void unitOfMeasureSearchWorksWithoutDateFilters() throws Exception {
        String code = "uom-search-" + System.nanoTime();
        mockMvc.perform(post("/api/uom")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code":"%s",
                      "measurementNameShort":"%s",
                      "measurementNameLong":"Searchable UOM",
                      "unitType":"MAIN"
                    }
                    """.formatted(code, code)))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/api/uom")
                .with(user("admin").roles("ADMIN"))
                .param("search", code))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"code\":\"" + code + "\"")));
    }

    @Test
    void companyApiRejectsInvalidTaxIdOkedAndEmail() throws Exception {
        mockMvc.perform(post("/api/companies")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code":"CMP-VAL-%s",
                      "name":"Validation Company",
                      "status":"ACTIVE",
                      "taxId":"307123456hj",
                      "okedCode":"4690A",
                      "email":"not-an-email"
                    }
                    """.formatted(System.nanoTime())))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(containsString("taxId")))
            .andExpect(content().string(containsString("okedCode")))
            .andExpect(content().string(containsString("email")));
    }

    @Test
    void auditorsAndViewersCanReadButCannotMutateBusinessData() throws Exception {
        mockMvc.perform(get("/api/materials").with(user("auditor").roles("AUDITOR")))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/materials")
                .with(user("auditor").roles("AUDITOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code":"MAT-AUDIT-WRITE","name":"Audit write attempt"}
                    """))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/reports/dashboard").with(user("viewer").roles("VIEWER")))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/materials")
                .with(user("viewer").roles("VIEWER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code":"MAT-VIEWER-WRITE","name":"Viewer write attempt"}
                    """))
            .andExpect(status().isForbidden());
    }

    @Test
    void roleBoundariesRejectCrossDomainMutations() throws Exception {
        mockMvc.perform(post("/api/purchase-orders")
                .with(user("warehouse").roles("WAREHOUSE_OPERATOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code":"PO-WH-BLOCKED","name":"Blocked"}
                    """))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/stock/movements")
                .with(user("buyer").roles("PROCUREMENT_SPECIALIST"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code":"SM-PRC-BLOCKED","name":"Blocked"}
                    """))
            .andExpect(status().isForbidden());
    }

    @Test
    void restrictedRolesWithoutEmployeeMappingCannotSeeScopedBusinessData() throws Exception {
        PurchaseRequest purchaseRequest = purchaseRequest("PR-SCOPE-NO-EMP-" + System.nanoTime(), "someone-else");
        StockBatch stockBatch = stockBatch("BATCH-SCOPE-NO-EMP-" + System.nanoTime());

        String procurementResponse = mockMvc.perform(get("/api/purchase-requests")
                .with(user("unknown-buyer").roles("PROCUREMENT_SPECIALIST"))
                .param("search", purchaseRequest.getCode()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        org.assertj.core.api.Assertions.assertThat(objectMapper.readTree(procurementResponse).path("data").path("content")).isEmpty();

        String warehouseResponse = mockMvc.perform(get("/api/stock/batches")
                .with(user("unknown-warehouse").roles("WAREHOUSE_OPERATOR"))
                .param("search", stockBatch.getCode()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        org.assertj.core.api.Assertions.assertThat(objectMapper.readTree(warehouseResponse).path("data").path("content")).isEmpty();
    }

    @Test
    void restrictedRolesOnlySeeMappedEmployeeScope() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Warehouse ownWarehouse = warehouse("WH-SCOPE-OWN-" + suffix);
        Warehouse otherWarehouse = warehouse("WH-SCOPE-OTHER-" + suffix);
        employee("warehouse-scope-" + suffix, ownWarehouse);

        StockBatch ownBatch = stockBatch("BATCH-SCOPE-OWN-" + suffix, ownWarehouse);
        StockBatch otherBatch = stockBatch("BATCH-SCOPE-OTHER-" + suffix, otherWarehouse);

        String warehouseResponse = mockMvc.perform(get("/api/stock/batches")
                .with(user("warehouse-scope-" + suffix).roles("WAREHOUSE_OPERATOR"))
                .param("size", "100"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode warehouseContent = objectMapper.readTree(warehouseResponse).path("data").path("content");
        org.assertj.core.api.Assertions.assertThat(warehouseContent.toString()).contains(ownBatch.getCode());
        org.assertj.core.api.Assertions.assertThat(warehouseContent.toString()).doesNotContain(otherBatch.getCode());

        String buyer = "buyer-scope-" + suffix;
        resetTenantAndCompanyContext();
        Department ownDepartment = department("DEPT-SCOPE-OWN-" + suffix);
        Department otherDepartment = department("DEPT-SCOPE-OTHER-" + suffix);
        employee(buyer, null, ownDepartment);
        PurchaseRequest ownRequest = purchaseRequest("PR-SCOPE-OWN-" + suffix, buyer, ownDepartment.getId());
        PurchaseRequest otherRequest = purchaseRequest("PR-SCOPE-OTHER-" + suffix, "other-buyer-" + suffix, otherDepartment.getId());

        String procurementResponse = mockMvc.perform(get("/api/purchase-requests")
                .with(user(buyer).roles("PROCUREMENT_SPECIALIST"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .param("size", "100"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode procurementContent = objectMapper.readTree(procurementResponse).path("data").path("content");
        org.assertj.core.api.Assertions.assertThat(procurementContent.toString()).contains(ownRequest.getCode());
        org.assertj.core.api.Assertions.assertThat(procurementContent.toString()).doesNotContain(otherRequest.getCode());
    }

    @Test
    void warehouseCanReadAndReceivePurchaseOrdersOnly() throws Exception {
        mockMvc.perform(get("/api/purchase-orders").with(user("warehouse").roles("WAREHOUSE_OPERATOR")))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/purchase-orders/00000000-0000-0000-0000-000000000001/send")
                .with(user("warehouse").roles("WAREHOUSE_OPERATOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/purchase-orders/00000000-0000-0000-0000-000000000001/receive")
                .with(user("warehouse").roles("WAREHOUSE_OPERATOR"))
                .header("Idempotency-Key", "security-test-receipt-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"warehouseId":"00000000-0000-0000-0000-000000000001","version":0}
                    """))
            .andExpect(status().isNotFound());
    }

    @Test
    void tenderCrudRequiresProcurementPermission() throws Exception {
        mockMvc.perform(get("/api/tender/commission-members").with(user("viewer").roles("VIEWER")))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/tender/commission-members")
                .with(user("viewer").roles("VIEWER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code":"TCM-VIEWER-BLOCKED","name":"Blocked"}
                    """))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/tender/commission-members")
                .with(user("warehouse").roles("WAREHOUSE_OPERATOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code":"TCM-WH-BLOCKED","name":"Blocked"}
                    """))
            .andExpect(status().isForbidden());
    }

    @Test
    void excelReportIsAvailableForFinanceRole() throws Exception {
        mockMvc.perform(get("/api/reports/stock-balances.xlsx").with(user("finance").roles("FINANCE_CONTROLLER")))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @Test
    void operationalMetricsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/analytics/summary"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void openApiDocumentsJwtAndTenantSecuritySchemes() {
        var securitySchemes = openAPI.getComponents().getSecuritySchemes();

        org.assertj.core.api.Assertions.assertThat(securitySchemes)
            .containsKeys(OpenApiConfig.BEARER_AUTH, OpenApiConfig.TENANT_HEADER, OpenApiConfig.COMPANY_HEADER);
        org.assertj.core.api.Assertions.assertThat(securitySchemes.get(OpenApiConfig.BEARER_AUTH).getScheme())
            .isEqualTo("bearer");
        org.assertj.core.api.Assertions.assertThat(securitySchemes.get(OpenApiConfig.TENANT_HEADER).getName())
            .isEqualTo("X-Tenant-Id");
        org.assertj.core.api.Assertions.assertThat(securitySchemes.get(OpenApiConfig.COMPANY_HEADER).getName())
            .isEqualTo("X-Company-Id");
        org.assertj.core.api.Assertions.assertThat(openAPI.getSecurity())
            .anySatisfy(requirement -> org.assertj.core.api.Assertions.assertThat(requirement.keySet())
                .contains(OpenApiConfig.BEARER_AUTH, OpenApiConfig.TENANT_HEADER, OpenApiConfig.COMPANY_HEADER));
    }

    @Test
    void corsPreflightAllowsConfiguredFrontendOrigin() throws Exception {
        mockMvc.perform(options("/api/materials")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization,X-Tenant-Id,X-Company-Id,X-Warehouse-Id"))
            .andExpect(status().isOk())
            .andExpect(content().string(""))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string(
                "Access-Control-Allow-Headers", containsString("X-Warehouse-Id")));

        mockMvc.perform(options("/api/auth/login")
                .header("Origin", "http://localhost:5175")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type,X-Tenant-Id,X-Company-Id"))
            .andExpect(status().isOk())
            .andExpect(content().string(""))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Access-Control-Allow-Origin", "http://localhost:5175"));

        mockMvc.perform(options("/api/auth/login")
                .header("Origin", "http://localhost:5182")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type,X-Tenant-Id,X-Company-Id"))
            .andExpect(status().isOk())
            .andExpect(content().string(""))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Access-Control-Allow-Origin", "http://localhost:5182"));

        mockMvc.perform(options("/api/mm/ai/copilot/conversations/00000000-0000-0000-0000-000000000001")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "PATCH")
                .header("Access-Control-Request-Headers", "Authorization,Content-Type,X-Tenant-Id,X-Company-Id"))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string(
                "Access-Control-Allow-Origin", "http://localhost:5173"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string(
                "Access-Control-Allow-Methods", containsString("PATCH")));
    }

    @Test
    void notificationAndApprovalPreflightsAllowFrontendRequestHeaders() throws Exception {
        for (String path : new String[] {
            "/api/notifications?status=ACTIVE",
            "/api/approval-tasks/my/summary"
        }) {
            mockMvc.perform(options(path)
                    .header("Origin", "http://localhost:5173")
                    .header("Access-Control-Request-Method", "GET")
                    .header("Access-Control-Request-Headers", "authorization,content-type,x-tenant-id,x-company-id"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string(
                    "Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string(
                    "Access-Control-Allow-Headers", containsString("x-tenant-id")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string(
                    "Access-Control-Allow-Headers", containsString("x-company-id")));
        }
    }

    @Test
    void missingAuthenticatedRouteReturns404Envelope() throws Exception {
        mockMvc.perform(get("/api/not-existing-route").with(user("admin").roles("ADMIN")))
            .andExpect(status().isNotFound())
            .andExpect(content().string(containsString("Resource not found")));
    }

    @Test
    void invalidCrudQueryParametersReturnBadRequestEnvelope() throws Exception {
        mockMvc.perform(get("/api/materials")
                .with(user("admin").roles("ADMIN"))
                .param("size", "500"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(containsString("Size must be between")));

        mockMvc.perform(get("/api/materials")
                .with(user("admin").roles("ADMIN"))
                .param("sort", "doesNotExist,asc"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(containsString("Invalid filter or sort parameter")));
    }

    @Test
    void jwtTenantClaimScopesCrudAndRejectsHeaderMismatch() throws Exception {
        String tenantA = "plant-a";
        String tenantB = "plant-b";
        ensureTenantAdmin(tenantA);
        ensureTenantAdmin(tenantB);
        String tokenA = login(tenantA);
        String tokenB = login(tenantB);
        MaterialReferences tenantAReferences = materialReferences(tenantA, CompanyContext.DEFAULT_COMPANY_ID, "TENANT-A");

        mockMvc.perform(post("/api/materials")
                .header("Authorization", "Bearer " + tokenA)
                .header("X-Tenant-Id", tenantA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code":"MAT-TENANT-A",
                      "name":"Tenant A material",
                      "status":"ACTIVE",
                      "materialGroupId":"%s",
                      "unitOfMeasureId":"%s"
                    }
                    """.formatted(tenantAReferences.materialGroupId(), tenantAReferences.unitOfMeasureId())))
            .andExpect(status().isCreated());

        String tenantAList = mockMvc.perform(get("/api/materials")
                .header("Authorization", "Bearer " + tokenA)
                .header("X-Tenant-Id", tenantA)
                .param("search", "MAT-TENANT-A"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode tenantAContent = objectMapper.readTree(tenantAList).path("data").path("content");
        org.assertj.core.api.Assertions.assertThat(tenantAContent).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(tenantAContent.get(0).path("tenantId").asText()).isEqualTo(tenantA);

        String tenantBList = mockMvc.perform(get("/api/materials")
                .header("Authorization", "Bearer " + tokenB)
                .header("X-Tenant-Id", tenantB)
                .param("search", "MAT-TENANT-A"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        org.assertj.core.api.Assertions.assertThat(objectMapper.readTree(tenantBList).path("data").path("content")).isEmpty();

        mockMvc.perform(get("/api/materials")
                .header("Authorization", "Bearer " + tokenA)
                .header("X-Tenant-Id", tenantB))
            .andExpect(status().isForbidden())
            .andExpect(content().string(containsString("Tenant mismatch")));
    }

    @Test
    void jwtCompanyClaimScopesCrudAndRejectsHeaderMismatch() throws Exception {
        String tenantId = TenantContext.DEFAULT_TENANT;
        String companiesResponse = mockMvc.perform(get("/api/auth/companies")
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode companies = objectMapper.readTree(companiesResponse).path("data");
        org.assertj.core.api.Assertions.assertThat(companies).hasSizeGreaterThanOrEqualTo(2);
        String companyA = companies.get(0).path("id").asText();
        String companyB = companies.get(1).path("id").asText();
        String tokenA = login(tenantId, companyA);
        String tokenB = login(tenantId, companyB);
        MaterialReferences companyAReferences = materialReferences(tenantId, UUID.fromString(companyA), "COMPANY-A");

        mockMvc.perform(post("/api/materials")
                .header("Authorization", "Bearer " + tokenA)
                .header("X-Tenant-Id", tenantId)
                .header("X-Company-Id", companyA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code":"MAT-COMPANY-A",
                      "name":"Company A material",
                      "status":"ACTIVE",
                      "materialGroupId":"%s",
                      "unitOfMeasureId":"%s"
                    }
                    """.formatted(companyAReferences.materialGroupId(), companyAReferences.unitOfMeasureId())))
            .andExpect(status().isCreated());

        String companyAList = mockMvc.perform(get("/api/materials")
                .header("Authorization", "Bearer " + tokenA)
                .header("X-Tenant-Id", tenantId)
                .header("X-Company-Id", companyA)
                .param("search", "MAT-COMPANY-A"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode companyAContent = objectMapper.readTree(companyAList).path("data").path("content");
        org.assertj.core.api.Assertions.assertThat(companyAContent).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(companyAContent.get(0).path("companyId").asText()).isEqualTo(companyA);

        String companyBList = mockMvc.perform(get("/api/materials")
                .header("Authorization", "Bearer " + tokenB)
                .header("X-Tenant-Id", tenantId)
                .header("X-Company-Id", companyB)
                .param("search", "MAT-COMPANY-A"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        org.assertj.core.api.Assertions.assertThat(objectMapper.readTree(companyBList).path("data").path("content")).isEmpty();

        mockMvc.perform(get("/api/materials")
                .header("Authorization", "Bearer " + tokenA)
                .header("X-Tenant-Id", tenantId)
                .header("X-Company-Id", companyB))
            .andExpect(status().isForbidden())
            .andExpect(content().string(containsString("Company mismatch")));
    }

    @Test
    void mfaChallengeRequiresTotpCodeAfterPassword() throws Exception {
        String tenantId = "tenant-mfa";
        ensureTenantAdmin(tenantId);
        String token = login(tenantId);

        String setupResponse = mockMvc.perform(get("/api/mfa/setup")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String secret = objectMapper.readTree(setupResponse).path("data").path("secret").asText();
        String code = totpService.currentCode(secret);

        mockMvc.perform(post("/api/mfa/enable")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"%s\"}".formatted(code)))
            .andExpect(status().isOk());

        String challenge = mockMvc.perform(post("/api/auth/login")
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"admin","password":"Admin123!"}
                    """))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        org.assertj.core.api.Assertions.assertThat(objectMapper.readTree(challenge).path("data").path("mfaRequired").asBoolean()).isTrue();

        mockMvc.perform(post("/api/auth/login")
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"admin","password":"Admin123!","mfaCode":"%s"}
                    """.formatted(totpService.currentCode(secret))))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("token")));
    }

    @Test
    void sensitiveSupplierFieldsAreEncryptedAtRest() {
        Supplier supplier = new Supplier();
        supplier.setCode("SUP-ENC-1");
        supplier.setName("Encrypted supplier");
        supplier.setStatus("ACTIVE");
        supplier.setTaxId("307123456");
        supplier.setBankMfo("00444");
        supplier.setBankAccount("20208000900123456789");
        Supplier saved = supplierRepository.saveAndFlush(supplier);

        String rawTaxId = jdbcTemplate.queryForObject("select tax_id from suppliers where id = ?", String.class, saved.getId());
        String rawAccount = jdbcTemplate.queryForObject("select bank_account from suppliers where id = ?", String.class, saved.getId());
        org.assertj.core.api.Assertions.assertThat(rawTaxId).startsWith("sec:v1:");
        org.assertj.core.api.Assertions.assertThat(rawAccount).startsWith("sec:v1:");
        org.assertj.core.api.Assertions.assertThat(supplierRepository.findAnyById(saved.getId()).orElseThrow().getTaxId()).isEqualTo("307123456");
    }

    @Test
    void productionPreflightReportsExternalReadinessBlockers() throws Exception {
        mockMvc.perform(get("/api/integrations/preflight").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"productionReady\":false")))
            .andExpect(content().string(containsString("integration.SOLIQ.mode")))
            .andExpect(content().string(containsString("operations.load-test")));

        mockMvc.perform(get("/api/integrations/preflight").with(user("auditor").roles("AUDITOR")))
            .andExpect(status().isOk());
    }

    @Test
    void productionPreflightIsHiddenFromViewer() throws Exception {
        mockMvc.perform(get("/api/integrations/preflight").with(user("viewer").roles("VIEWER")))
            .andExpect(status().isForbidden());
    }

    @Test
    void externalProviderDocumentApisAreAvailableInMockMode() throws Exception {
        resetSoliqToMock();

        mockMvc.perform(get("/api/settings/integrations/SOLIQ/documents/EXT-100/status")
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("MOCK_ACCEPTED")));

        mockMvc.perform(get("/api/settings/integrations/SOLIQ/documents/incoming")
                .with(user("admin").roles("ADMIN"))
                .param("dateFrom", "2026-01-01")
                .param("dateTo", "2026-12-31")
                .param("status", "NEW"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"direction\":\"INCOMING\"")));

        mockMvc.perform(post("/api/settings/integrations/SOLIQ/documents/EXT-100/cancel")
                .with(user("admin").roles("ADMIN"))
                .header("Idempotency-Key", "soliq-cancel-api-test")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason":"Ошибка документа"}
                    """))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("SOLIQ.CANCEL_DOCUMENT")));

        mockMvc.perform(get("/api/settings/integrations/SOLIQ/documents/EXT-100/download")
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("X-Archived-Attachment-Id"))
            .andExpect(content().string(containsString("EXT-100")));

        mockMvc.perform(post("/api/settings/integrations/SOLIQ/documents/EXT-100/archive-original")
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("PROVIDER_ORIGINAL_DOCUMENT")))
            .andExpect(content().string(containsString("soliq-EXT-100")));

        mockMvc.perform(get("/api/settings/integrations/SOLIQ/documents/EXT-100/originals")
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("PROVIDER_ORIGINAL_DOCUMENT")))
            .andExpect(content().string(containsString("soliq-EXT-100")));
    }

    @Test
    void providerCallbacksAndEimzoDocumentApisAreAvailable() throws Exception {
        mockMvc.perform(post("/api/integrations/callbacks/SOLIQ")
                .with(user("admin").roles("ADMIN"))
                .header("Idempotency-Key", "soliq-callback-api-test")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "eventType":"DOCUMENT_STATUS_CHANGED",
                      "externalId":"EXT-200",
                      "status":"SIGNED",
                      "payload":{"documentNo":"SF-200"}
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Callback accepted")))
            .andExpect(content().string(containsString("EXT-200")));

        mockMvc.perform(post("/api/integrations/callbacks/SOLIQ")
                .with(user("integration-service").roles("INTEGRATION_OPERATOR"))
                .header("Idempotency-Key", "soliq-integration-operator-callback-test")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "eventType":"DOCUMENT_STATUS_CHANGED",
                      "externalId":"EXT-201",
                      "status":"SIGNED",
                      "payload":{"documentNo":"SF-201"}
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Callback accepted")))
            .andExpect(content().string(containsString("EXT-201")));

        mockMvc.perform(post("/api/integrations/e-imzo/documents/verify")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "entityType":"CONTRACT",
                      "entityId":"00000000-0000-0000-0000-000000000001",
                      "signaturePayload":"payload-to-sign",
                      "pkcs7":"pkcs7-signature",
                      "certificateSerial":"AA001122",
                      "signerTin":"307123456"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"verified\":true")));

        mockMvc.perform(post("/api/integrations/e-imzo/documents/sign")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "entityType":"CONTRACT",
                      "entityId":"00000000-0000-0000-0000-000000000001",
                      "signaturePayload":"payload-to-sign",
                      "pkcs7":"pkcs7-signature",
                      "certificateSerial":"AA001122",
                      "signerTin":"307123456"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"verified\":true")));
    }

    @Test
    void jwtRefreshAndLogoutApiContractsWork() throws Exception {
        String token = login("default");

        mockMvc.perform(post("/api/auth/refresh")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", "default"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"token\"")));

        mockMvc.perform(post("/api/auth/logout")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", "default"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("logout-accepted")));
    }

    @Test
    void meApiReturnsCurrentUserProfile() throws Exception {
        String token = login("default");

        mockMvc.perform(get("/api/auth/me")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", "default"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"username\":\"admin\"")))
            .andExpect(content().string(containsString("\"tenantId\":\"default\"")))
            .andExpect(content().string(containsString("\"departmentId\"")))
            .andExpect(content().string(containsString("\"roles\"")))
            .andExpect(content().string(containsString("\"enabled\":true")));
    }

    @Test
    void rolesApiCanCreateGlobalRoleWithoutCompany() throws Exception {
        String roleCode = "GLOBAL_VIEWER_" + System.nanoTime();
        String roleName = "GLOBAL_VIEWER_" + System.nanoTime();

        mockMvc.perform(post("/api/roles")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code":"%s",
                      "name":"Global Viewer",
                      "roleName":"%s",
                      "globalRole":true,
                      "status":"ACTIVE"
                    }
                    """.formatted(roleCode, roleName)))
            .andExpect(status().isCreated())
            .andExpect(content().string(containsString("\"companyId\":null")))
            .andExpect(content().string(containsString("\"globalRole\":true")))
            .andExpect(content().string(containsString("\"roleName\":\"" + roleName + "\"")));

        mockMvc.perform(get("/api/roles")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .param("search", roleCode))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"companyId\":null")))
            .andExpect(content().string(containsString("\"globalRole\":true")));
    }

    @Test
    void rolesApiReturnsOnlyCurrentCompanyAndGlobalRoles() throws Exception {
        UUID companyId = CompanyContext.DEFAULT_COMPANY_ID;

        String response = mockMvc.perform(get("/api/roles")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", companyId.toString())
                .param("size", "100"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode content = objectMapper.readTree(response).path("data").path("content");
        Iterator<JsonNode> iterator = content.elements();
        while (iterator.hasNext()) {
            JsonNode role = iterator.next();
            JsonNode roleCompanyId = role.path("companyId");
            if (!roleCompanyId.isNull()) {
                org.assertj.core.api.Assertions.assertThat(roleCompanyId.asText()).isEqualTo(companyId.toString());
            }
        }
    }

    @Test
    void usersApiCreatesCompanylessSuperAdminFromGlobalAdminRole() throws Exception {
        String username = "api_super_" + System.nanoTime();
        mockMvc.perform(post("/api/users")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username":"%s",
                      "password":"Admin123!",
                      "name":"API Super Admin",
                      "fullName":"API Super Admin",
                      "email":"%s@example.com",
                      "enabled":true,
                      "roles":["ADMIN"],
                      "status":"ACTIVE"
                    }
                    """.formatted(username, username)))
            .andExpect(status().isCreated())
            .andExpect(content().string(containsString("\"username\":\"" + username + "\"")))
            .andExpect(content().string(containsString("\"companyId\":null")))
            .andExpect(content().string(containsString("\"superAdmin\":true")))
            .andExpect(content().string(containsString("\"roles\":[\"ADMIN\"]")));

        mockMvc.perform(get("/api/users")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .param("search", "API Super Admin"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"username\":\"" + username + "\"")))
            .andExpect(content().string(containsString("\"companyId\":null")))
            .andExpect(content().string(containsString("\"superAdmin\":true")));
    }

    @Test
    void usersApiKeepsCompanyIdForAdminWhenCompanyIsExplicitlyProvided() throws Exception {
        String companyId = CompanyContext.DEFAULT_COMPANY_ID.toString();
        String username = "api_company_only_admin_" + System.nanoTime();

        mockMvc.perform(post("/api/users")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", companyId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username":"%s",
                      "password":"Admin123!",
                      "name":"Company Only Admin",
                      "fullName":"Company Only Admin",
                      "email":"%s@example.com",
                      "companyId":"%s",
                      "enabled":true,
                      "roles":["ADMIN"],
                      "status":"ACTIVE"
                    }
                    """.formatted(username, username, companyId)))
            .andExpect(status().isCreated())
            .andExpect(content().string(containsString("\"username\":\"" + username + "\"")))
            .andExpect(content().string(containsString("\"companyId\":\"" + companyId + "\"")))
            .andExpect(content().string(containsString("\"superAdmin\":false")))
            .andExpect(content().string(containsString("\"roles\":[\"ADMIN\"]")));
    }

    @Test
    void usersApiCreatesCompanyScopedAdminWhenDepartmentIsSelected() throws Exception {
        String companyId = CompanyContext.DEFAULT_COMPANY_ID.toString();
        String departmentId = jdbcTemplate.queryForObject(
            "select id from departments where tenant_id = ? and company_id = ? and is_delete = false limit 1",
            String.class,
            "default",
            companyId
        );
        String username = "api_company_admin_" + System.nanoTime();

        mockMvc.perform(post("/api/users")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", companyId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username":"%s",
                      "password":"Admin123!",
                      "name":"Company Admin",
                      "fullName":"Company Admin",
                      "email":"%s@example.com",
                      "companyId":"%s",
                      "departmentId":"%s",
                      "enabled":true,
                      "roles":["ADMIN","MM_MANAGER","PROCUREMENT_SPECIALIST"],
                      "status":"ACTIVE"
                    }
                    """.formatted(username, username, companyId, departmentId)))
            .andExpect(status().isCreated())
            .andExpect(content().string(containsString("\"username\":\"" + username + "\"")))
            .andExpect(content().string(containsString("\"companyId\":\"" + companyId + "\"")))
            .andExpect(content().string(containsString("\"departmentId\":\"" + departmentId + "\"")))
            .andExpect(content().string(containsString("\"superAdmin\":false")));
    }

    @Test
    void usersApiDefaultsCreatedUsersToEnabledWhenFieldIsOmitted() throws Exception {
        String username = "api_enabled_default_" + System.nanoTime();

        mockMvc.perform(post("/api/users")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username":"%s",
                      "password":"Admin123!",
                      "name":"Enabled By Default",
                      "fullName":"Enabled By Default",
                      "email":"%s@example.com",
                      "roles":["PROCUREMENT_SPECIALIST"],
                      "status":"ACTIVE"
                    }
                    """.formatted(username, username)))
            .andExpect(status().isCreated())
            .andExpect(content().string(containsString("\"username\":\"" + username + "\"")))
            .andExpect(content().string(containsString("\"enabled\":true")));
    }

    @Test
    void usersApiAttachesSelectedEmployeeOnCreate() throws Exception {
        String companyId = CompanyContext.DEFAULT_COMPANY_ID.toString();
        String suffix = Long.toString(System.nanoTime());
        String employeeResponse = mockMvc.perform(post("/api/employees")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", companyId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name":"Linked Employee %s",
                      "personnelNumber":"EMP-LINK-%s",
                      "email":"linked.employee.%s@example.com",
                      "employmentStatus":"ACTIVE",
                      "status":"ACTIVE"
                    }
                    """.formatted(suffix, suffix, suffix)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String employeeId = objectMapper.readTree(employeeResponse).path("data").path("id").asText();
        String username = "api_user_employee_" + suffix;

        mockMvc.perform(post("/api/users")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", companyId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username":"%s",
                      "password":"Admin123!",
                      "name":"User With Employee",
                      "fullName":"User With Employee",
                      "email":"user.employee.%s@example.com",
                      "employeeId":"%s",
                      "enabled":true,
                      "roles":["PROCUREMENT_SPECIALIST"],
                      "status":"ACTIVE"
                    }
                    """.formatted(username, suffix, employeeId)))
            .andExpect(status().isCreated())
            .andExpect(content().string(containsString("\"username\":\"" + username + "\"")))
            .andExpect(content().string(containsString("\"employeeId\":\"" + employeeId + "\"")));
    }

    @Test
    void employeesApiAttachesSelectedUserOnCreate() throws Exception {
        String companyId = CompanyContext.DEFAULT_COMPANY_ID.toString();
        String suffix = Long.toString(System.nanoTime());
        String username = "api_employee_user_" + suffix;
        String userResponse = mockMvc.perform(post("/api/users")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", companyId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username":"%s",
                      "password":"Admin123!",
                      "name":"Attachable User",
                      "fullName":"Attachable User",
                      "email":"attachable.user.%s@example.com",
                      "enabled":true,
                      "roles":["PROCUREMENT_SPECIALIST"],
                      "status":"ACTIVE"
                    }
                    """.formatted(username, suffix)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String userId = objectMapper.readTree(userResponse).path("data").path("id").asText();

        mockMvc.perform(post("/api/employees")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", companyId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name":"Employee With User %s",
                      "personnelNumber":"USR-LINK-%s",
                      "email":"employee.user.%s@example.com",
                      "userId":"%s",
                      "employmentStatus":"ACTIVE",
                      "status":"ACTIVE"
                    }
                    """.formatted(suffix, suffix, suffix, userId)))
            .andExpect(status().isCreated())
            .andExpect(content().string(containsString("\"userId\":\"" + userId + "\"")));
    }

    @Test
    void globalSuperAdminUsersApiListsUsersAcrossCompanies() throws Exception {
        String token = loginSuperAdmin();

        mockMvc.perform(get("/api/users")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", "default"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"username\":\"super_admin\"")))
            .andExpect(content().string(containsString("\"code\":\"USR-ADMIN-TST\"")))
            .andExpect(content().string(containsString("\"code\":\"USR-ADMIN-AND-REM\"")));
    }

    @Test
    void globalSuperAdminCanMoveUserToAnotherCompany() throws Exception {
        String tenantId = "default";
        String defaultCompany = CompanyContext.DEFAULT_COMPANY_ID.toString();
        String andRemCompany = jdbcTemplate.queryForObject(
            "select id from companies where tenant_id = ? and code = ? and is_delete = false",
            String.class,
            tenantId,
            "AND-REM"
        );
        String username = "api_move_user_" + System.nanoTime();

        String created = mockMvc.perform(post("/api/users")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId)
                .header("X-Company-Id", defaultCompany)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username":"%s",
                      "password":"Admin123!",
                      "name":"Movable User",
                      "fullName":"Movable User",
                      "email":"%s@example.com",
                      "enabled":true,
                      "roles":["PROCUREMENT_SPECIALIST"],
                      "status":"ACTIVE"
                    }
                    """.formatted(username, username)))
            .andExpect(status().isCreated())
            .andExpect(content().string(containsString("\"companyId\":\"" + defaultCompany + "\"")))
            .andReturn()
            .getResponse()
            .getContentAsString();
        String userId = objectMapper.readTree(created).path("data").path("id").asText();

        String token = loginSuperAdmin();
        mockMvc.perform(put("/api/users/{id}", userId)
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username":"%s",
                      "name":"Moved User",
                      "fullName":"Moved User",
                      "email":"%s@example.com",
                      "companyId":"%s",
                      "enabled":true,
                      "roles":["PROCUREMENT_SPECIALIST"],
                      "status":"ACTIVE"
                    }
                    """.formatted(username, username, andRemCompany)))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"companyId\":\"" + andRemCompany + "\"")));

        org.assertj.core.api.Assertions.assertThat(userRepository.findById(UUID.fromString(userId)).orElseThrow().getCompanyId())
            .isEqualTo(UUID.fromString(andRemCompany));
    }

    @Test
    void companyScopedAdminCannotMoveUserToAnotherCompany() throws Exception {
        String tenantId = "default";
        String defaultCompany = CompanyContext.DEFAULT_COMPANY_ID.toString();
        String andRemCompany = jdbcTemplate.queryForObject(
            "select id from companies where tenant_id = ? and code = ? and is_delete = false",
            String.class,
            tenantId,
            "AND-REM"
        );
        String username = "api_block_move_user_" + System.nanoTime();

        String created = mockMvc.perform(post("/api/users")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId)
                .header("X-Company-Id", defaultCompany)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username":"%s",
                      "password":"Admin123!",
                      "name":"Blocked Move User",
                      "fullName":"Blocked Move User",
                      "email":"%s@example.com",
                      "enabled":true,
                      "roles":["PROCUREMENT_SPECIALIST"],
                      "status":"ACTIVE"
                    }
                    """.formatted(username, username)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String userId = objectMapper.readTree(created).path("data").path("id").asText();
        String token = login(tenantId, defaultCompany);

        mockMvc.perform(put("/api/users/{id}", userId)
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .header("X-Company-Id", defaultCompany)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username":"%s",
                      "name":"Blocked Move User",
                      "fullName":"Blocked Move User",
                      "email":"%s@example.com",
                      "companyId":"%s",
                      "enabled":true,
                      "roles":["PROCUREMENT_SPECIALIST"],
                      "status":"ACTIVE"
                    }
                    """.formatted(username, username, andRemCompany)))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(containsString("Company is not accessible")));

        org.assertj.core.api.Assertions.assertThat(userRepository.findById(UUID.fromString(userId)).orElseThrow().getCompanyId())
            .isEqualTo(UUID.fromString(defaultCompany));
    }

    @Test
    void globalSuperAdminCanManageCompanyDataAcrossCompanies() throws Exception {
        String tenantId = "default";
        String defaultCompany = CompanyContext.DEFAULT_COMPANY_ID.toString();
        String andRemCompany = jdbcTemplate.queryForObject(
            "select id from companies where tenant_id = ? and code = ? and is_delete = false",
            String.class,
            tenantId,
            "AND-REM"
        );
        String code = "SUP-GLOBAL-" + System.nanoTime();
        String andRemToken = login(tenantId, andRemCompany);
        String defaultToken = login(tenantId, defaultCompany);

        String created = mockMvc.perform(post("/api/suppliers")
                .header("Authorization", "Bearer " + andRemToken)
                .header("X-Tenant-Id", tenantId)
                .header("X-Company-Id", andRemCompany)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code":"%s",
                      "name":"Global scope supplier",
                      "legalName":"Global scope supplier LLC",
                      "taxId":"307654321",
                      "registrationNumber":"REG-%s",
                      "legalAddress":"Tashkent street 1",
                      "email":"global.%s@example.com",
                      "expectedVersion":0,
                      "status":"ACTIVE"
                    }
                    """.formatted(code, code, code.toLowerCase())))
            .andExpect(status().isCreated())
            .andExpect(content().string(containsString("\"companyId\":\"" + andRemCompany + "\"")))
            .andReturn()
            .getResponse()
            .getContentAsString();
        String supplierId = objectMapper.readTree(created).path("data").path("id").asText();

        String defaultCompanyList = mockMvc.perform(get("/api/suppliers")
                .header("Authorization", "Bearer " + defaultToken)
                .header("X-Tenant-Id", tenantId)
                .header("X-Company-Id", defaultCompany)
                .param("search", code))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        org.assertj.core.api.Assertions.assertThat(objectMapper.readTree(defaultCompanyList).path("data").path("content")).isEmpty();

        String superAdminToken = loginSuperAdmin();
        mockMvc.perform(get("/api/suppliers")
                .header("Authorization", "Bearer " + superAdminToken)
                .header("X-Tenant-Id", tenantId)
                .param("search", code))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"code\":\"" + code + "\"")))
            .andExpect(content().string(containsString("\"companyId\":\"" + andRemCompany + "\"")));

        mockMvc.perform(put("/api/suppliers/" + supplierId)
                .header("Authorization", "Bearer " + superAdminToken)
                .header("X-Tenant-Id", tenantId)
                .header("X-Company-Id", andRemCompany)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code":"%s",
                      "name":"Global scope supplier updated",
                      "legalName":"Global scope supplier LLC",
                      "taxId":"307654321",
                      "registrationNumber":"REG-%s",
                      "legalAddress":"Tashkent street 1",
                      "email":"global.%s@example.com",
                      "expectedVersion":0,
                      "status":"ACTIVE"
                    }
                    """.formatted(code, code, code.toLowerCase())))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"name\":\"Global scope supplier updated\"")))
            .andExpect(content().string(containsString("\"companyId\":\"" + andRemCompany + "\"")));
    }

    @Test
    void supplierSearchSupportsEncryptedTaxIdFilter() throws Exception {
        String code = "SUP-TAX-SEARCH-" + System.nanoTime();
        String taxId = "307123" + (System.nanoTime() % 1000);

        mockMvc.perform(post("/api/suppliers")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code":"%s",
                      "name":"Supplier tax search",
                      "legalName":"Supplier tax search LLC",
                      "registrationNumber":"REG-%s",
                      "legalAddress":"Tashkent street 1",
                      "email":"tax.%s@example.com",
                      "status":"ACTIVE",
                      "taxId":"%s"
                    }
                    """.formatted(code, code, code.toLowerCase(), taxId)))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/api/suppliers")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .param("taxId", taxId.substring(3)))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"code\":\"" + code + "\"")))
            .andExpect(content().string(containsString("\"taxId\":\"" + taxId + "\"")));
    }

    @Test
    void supplierSearchSupportsEncryptedBankAccountFilter() throws Exception {
        String code = "SUP-BANK-SEARCH-" + System.nanoTime();
        String bankAccount = "20208000" + (System.nanoTime() % 1_000_000_000L);

        mockMvc.perform(post("/api/suppliers")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code":"%s",
                      "name":"Supplier bank search",
                      "legalName":"Supplier bank search LLC",
                      "taxId":"307987654",
                      "registrationNumber":"REG-%s",
                      "legalAddress":"Tashkent street 1",
                      "email":"bank.%s@example.com",
                      "status":"ACTIVE",
                      "bankName":"Search bank",
                      "bankMfo":"00999",
                      "bankAccount":"%s"
                    }
                    """.formatted(code, code, code.toLowerCase(), bankAccount)))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/api/suppliers")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .param("bankAccount", bankAccount.substring(bankAccount.length() - 6)))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"code\":\"" + code + "\"")))
            .andExpect(content().string(containsString("\"bankAccount\":\"" + bankAccount + "\"")));
    }

    @Test
    void unitOfMeasureSearchReturnsTenantWideResultsAcrossCompanies() throws Exception {
        String tenantId = "default";
        String defaultCompany = CompanyContext.DEFAULT_COMPANY_ID.toString();
        String andRemCompany = jdbcTemplate.queryForObject(
            "select id from companies where tenant_id = ? and code = ? and is_delete = false",
            String.class,
            tenantId,
            "AND-REM"
        );
        String code = "UOM-GLOBAL-" + System.nanoTime();
        String andRemToken = login(tenantId, andRemCompany);
        String defaultToken = login(tenantId, defaultCompany);

        mockMvc.perform(post("/api/uom")
                .header("Authorization", "Bearer " + andRemToken)
                .header("X-Tenant-Id", tenantId)
                .header("X-Company-Id", andRemCompany)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code":"%s",
                      "measurementNameShort":"%s",
                      "measurementNameLong":"Cross company visible UOM",
                      "unitType":"MAIN"
                    }
                    """.formatted(code, code)))
            .andExpect(status().isCreated())
            .andExpect(content().string(containsString("\"companyId\":\"" + andRemCompany + "\"")));

        mockMvc.perform(get("/api/uom")
                .header("Authorization", "Bearer " + defaultToken)
                .header("X-Tenant-Id", tenantId)
                .header("X-Company-Id", defaultCompany)
                .param("search", code))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"code\":\"" + code + "\"")))
            .andExpect(content().string(containsString("\"companyId\":\"" + andRemCompany + "\"")));
    }

    @Test
    void seededSuperAdminLoginAndMeStayCompanyless() throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username":"super_admin",
                      "password":"Admin123!"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"username\":\"super_admin\"")))
            .andExpect(content().string(containsString("\"companyId\":null")))
            .andExpect(content().string(containsString("\"companyName\":null")))
            .andExpect(content().string(containsString("\"roles\":[\"ROLE_ADMIN\"]")))
            .andExpect(content().string(containsString("\"mfaRequired\":false")))
            .andReturn()
            .getResponse()
            .getContentAsString();

        String token = objectMapper.readTree(response).path("data").path("token").asText();
        mockMvc.perform(get("/api/auth/me")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"username\":\"super_admin\"")))
            .andExpect(content().string(containsString("\"companyId\":null")))
            .andExpect(content().string(containsString("\"companyName\":null")))
            .andExpect(content().string(containsString("\"departmentId\":null")))
            .andExpect(content().string(containsString("\"roles\":[\"ROLE_ADMIN\"]")));
    }

    private String login(String tenantId) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"admin","password":"Admin123!"}
                    """))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private String login(String tenantId, String companyId) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                .header("X-Tenant-Id", tenantId)
                .header("X-Company-Id", companyId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"admin","password":"Admin123!"}
                    """))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private String loginSuperAdmin() throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                .header("X-Tenant-Id", "default")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"super_admin","password":"Admin123!"}
                    """))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private void ensureTenantAdmin(String tenantId) {
        String previousTenant = TenantContext.currentTenant();
        TenantContext.setTenant(tenantId);
        try {
            roleRepository.findByTenantIdAndRoleNameAndIsDeletedFalse(tenantId, RoleName.ADMIN.name()).orElseGet(() -> {
                Role created = new Role();
                created.setCode(RoleName.ADMIN.name());
                created.setName(RoleName.ADMIN.name());
                created.setRoleName(RoleName.ADMIN.name());
                created.setStatus("ACTIVE");
                return roleRepository.save(created);
            });
            User admin = userRepository.findByTenantIdAndUsernameAndIsDeletedFalse(tenantId, "admin").orElseGet(User::new);
            admin.setCode("USR-ADMIN-" + tenantId);
            admin.setName("Tenant Administrator " + tenantId);
            admin.setUsername("admin");
            admin.setFullName("Tenant Administrator");
            admin.setEmail("admin+" + tenantId + "@example.com");
            admin.setStatus("ACTIVE");
            admin.setEnabled(true);
            admin.setMfaEnabled(false);
            admin.setMfaSecret(null);
            admin.setPasswordHash(passwordEncoder.encode("Admin123!"));
            admin.setRoles(new HashSet<>(java.util.Set.of(RoleName.ADMIN)));
            userRepository.save(admin);
        } finally {
            TenantContext.setTenant(previousTenant);
        }
    }

    private MaterialReferences materialReferences(String tenantId, UUID companyId, String suffix) {
        String previousTenant = TenantContext.currentTenant();
        UUID previousCompany = CompanyContext.currentCompany();
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);
        try {
            MaterialGroup group = new MaterialGroup();
            group.setCode("MG-" + suffix);
            group.setName("Material group " + suffix);
            group.setStatus("ACTIVE");
            group = materialGroupRepository.save(group);

            UnitOfMeasure unit = new UnitOfMeasure();
            unit.setCode("UOM-" + suffix);
            unit.setName("Unit " + suffix);
            unit.setStatus("ACTIVE");
            unit.setMeasurementNameShort("pcs");
            unit.setMeasurementNameLong("Pieces");
            unit = unitOfMeasureRepository.save(unit);

            return new MaterialReferences(group.getId(), unit.getId());
        } finally {
            TenantContext.setTenant(previousTenant);
            CompanyContext.setCompany(previousCompany);
        }
    }

    private record MaterialReferences(UUID materialGroupId, UUID unitOfMeasureId) {
    }

    private Employee employee(String code, Warehouse warehouse) {
        return employee(code, warehouse, null);
    }

    private Employee employee(String code, Warehouse warehouse, Department department) {
        Employee employee = new Employee();
        employee.setCode(code);
        employee.setName(code);
        employee.setStatus("ACTIVE");
        employee.setPersonnelNumber(code);
        employee.setEmail(code + "@example.com");
        employee.setWarehouse(warehouse);
        employee.setDepartment(department);
        return employeeRepository.save(employee);
    }

    private PurchaseRequest purchaseRequest(String code, String requestedBy) {
        return purchaseRequest(code, requestedBy, null);
    }

    private PurchaseRequest purchaseRequest(String code, String requestedBy, UUID departmentId) {
        PurchaseRequest request = new PurchaseRequest();
        request.setCode(code);
        request.setName(code);
        request.setStatus("DRAFT");
        request.setRequestedBy(requestedBy);
        request.setDepartmentId(departmentId);
        request.setTotalAmount(BigDecimal.TEN);
        request.setCurrencyCode("UZS");
        return purchaseRequestRepository.save(request);
    }

    private Department department(String code) {
        Department department = new Department();
        department.setCode(code);
        department.setName(code);
        department.setStatus("ACTIVE");
        return departmentRepository.save(department);
    }

    private StockBatch stockBatch(String code) {
        return stockBatch(code, warehouse("WH-" + code));
    }

    private StockBatch stockBatch(String code, Warehouse warehouse) {
        Material material = material("MAT-" + code);
        StockBatch batch = new StockBatch();
        batch.setCode(code);
        batch.setName(code);
        batch.setStatus("ACTIVE");
        batch.setMaterialId(material.getId());
        batch.setWarehouseId(warehouse.getId());
        batch.setBatchNumber(code);
        batch.setQuantity(BigDecimal.TEN);
        return stockBatchRepository.save(batch);
    }

    private Material material(String code) {
        Material material = new Material();
        material.setCode(code);
        material.setName(code);
        material.setStatus("ACTIVE");
        return materialRepository.save(material);
    }

    private Warehouse warehouse(String code) {
        Warehouse warehouse = new Warehouse();
        warehouse.setCode(code);
        warehouse.setName(code);
        warehouse.setStatus("ACTIVE");
        return warehouseRepository.save(warehouse);
    }

    private void resetSoliqToMock() throws Exception {
        mockMvc.perform(put("/api/settings/integrations/SOLIQ")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "systemCode":"SOLIQ",
                      "providerName":"Soliq Service",
                      "enabled":true,
                      "mockMode":true,
                      "baseUrl":"",
                      "authType":"NONE"
                    }
                    """))
            .andExpect(status().isOk());
    }
}
