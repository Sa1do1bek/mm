package com.enterprise.mm;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.common.CompanyContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.seed.enabled=false")
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class ReportsHttpSecurityTest {
    @Autowired MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(strings = {"DEPARTMENT_HEAD", "DEPARTMENT_WORKER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "WAREHOUSE_OPERATOR", "WAREHOUSE_KEEPER"})
    void rolesHiddenByFrontendCannotReadFinancialOrSupplierReports(String role) throws Exception {
        mockMvc.perform(get("/api/reports/snapshot")
                .with(user("out-of-scope").roles(role))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER", "BUDGET_CONTROLLER",
        "PROCUREMENT_MANAGER", "ACCOUNTANT",
        "EXECUTIVE", "AUDITOR", "VIEWER"
    })
    void frontendReportRolesCanReachTheReportEndpoint(String role) throws Exception {
        mockMvc.perform(get("/api/reports/snapshot")
                .with(user("in-scope").roles(role))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAREHOUSE_MANAGER", "WAREHOUSE_HEAD"})
    void warehouseLeadershipCannotImportOpeningStock(String role) throws Exception {
        mockMvc.perform(multipart("/api/reports/import/stock-balances")
                .file(invalidSpreadsheet())
                .with(user("warehouse-importer").roles(role))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/reports/import/materials",
        "/api/reports/import/reference-mappings",
        "/api/reports/import/ikpu-codes",
        "/api/reports/import/mxik-codes",
        "/api/reports/import/tnved-codes"
    })
    void warehouseManagerCannotReachMasterDataImports(String path) throws Exception {
        mockMvc.perform(multipart(path)
                .file(invalidSpreadsheet())
                .with(user("warehouse-importer").roles("WAREHOUSE_MANAGER"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "MM_MANAGER"})
    void masterDataRolesCanReachMasterDataImports(String role) throws Exception {
        mockMvc.perform(multipart("/api/reports/import/materials")
                .file(invalidSpreadsheet())
                .with(user("master-data-importer").roles(role))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(status().isUnprocessableEntity());
    }

    private MockMultipartFile invalidSpreadsheet() {
        return new MockMultipartFile("file", "invalid.txt", "text/plain", "not-xlsx".getBytes());
    }
}
