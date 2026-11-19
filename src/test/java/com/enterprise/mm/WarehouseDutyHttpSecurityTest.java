package com.enterprise.mm;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.common.CompanyContext;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "app.seed.enabled=false",
    // Keep the in-memory schema when Hikari replaces all connections after a clock adjustment.
    "spring.datasource.url=jdbc:h2:mem:warehouse-duty-${random.uuid};DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
})
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class WarehouseDutyHttpSecurityTest {
    @Autowired MockMvc mvc;

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "WAREHOUSE_OPERATOR", "WAREHOUSE_KEEPER", "AUDITOR", "VIEWER", "ACCOUNTANT", "FINANCE_CONTROLLER"})
    void personalThresholdSaveReachesValidationWithoutGrantingStockMutationRights(String role) throws Exception {
        mvc.perform(put("/api/warehouse/non-moving/preferences/me").with(user("personal-threshold").roles(role))
                .contentType(MediaType.APPLICATION_JSON).content("{\"thresholdMonths\":0,\"expectedVersion\":0}"))
            .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "WAREHOUSE_OPERATOR", "WAREHOUSE_KEEPER", "AUDITOR", "VIEWER", "ACCOUNTANT", "FINANCE_CONTROLLER"})
    void candidateExportUsesExistingReadRoles(String role) throws Exception {
        mvc.perform(get("/api/warehouses/" + UUID.randomUUID() + "/non-moving-candidates/export")
                .with(user("export-reader").roles(role)).param("language", "de"))
            .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEPARTMENT_WORKER", "DEPARTMENT_HEAD", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST"})
    void unrelatedRolesCannotReadOrWritePersonalWarehouseThresholds(String role) throws Exception {
        mvc.perform(put("/api/warehouse/non-moving/preferences/me").with(user("threshold-denied").roles(role))
                .contentType(MediaType.APPLICATION_JSON).content("{\"thresholdMonths\":24,\"expectedVersion\":0}"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/warehouse/non-moving/preferences/me").with(user("threshold-denied").roles(role)))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/warehouses/" + UUID.randomUUID() + "/non-moving-candidates")
                .with(user("threshold-denied").roles(role)))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/warehouses/" + UUID.randomUUID() + "/non-moving-candidates/export")
                .with(user("threshold-denied").roles(role)))
            .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "WAREHOUSE_OPERATOR", "WAREHOUSE_KEEPER"})
    void warehouseRolesReachManualCommandValidation(String role) throws Exception {
        for (String operation : new String[]{"receipt", "issue", "asset-receipt"})
            mvc.perform(post("/api/workspaces/warehouse-execution/manual/" + operation).with(user("warehouse-manual").roles(role))
                .header("Idempotency-Key", "manual-validation").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUDITOR", "VIEWER", "FINANCE_CONTROLLER", "DEPARTMENT_WORKER", "PROCUREMENT_MANAGER"})
    void readAndProcurementRolesCannotPostManualWarehouseCommands(String role) throws Exception {
        for (String operation : new String[]{"receipt", "issue", "asset-receipt"})
            mvc.perform(post("/api/workspaces/warehouse-execution/manual/" + operation).with(user("manual-reader").roles(role))
                .header("Idempotency-Key", "manual-validation").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "WAREHOUSE_OPERATOR", "WAREHOUSE_KEEPER"})
    void warehouseRolesCannotAdministerMastersOrUseCompanyFinance(String role) throws Exception {
        for (String path : new String[]{"/api/migration", "/api/overview/management", "/api/overview/operational",
            "/api/reports/snapshot", "/api/asset-opening-imports", "/api/rfid-tags"}) {
            mvc.perform(get(path).with(user("warehouse-audit").roles(role))
                    .header("X-Tenant-Id", "default").header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID))
                .andExpect(status().isForbidden());
        }
        for (String path : new String[]{"/api/warehouses", "/api/inventory/vmi-agreements", "/api/quality/laboratory-tests", "/api/quality/claims", "/api/inventory/consignment-transactions",
            "/api/inventory/optimization/" + UUID.randomUUID() + "/apply-to-material"}) {
            mvc.perform(post(path).with(user("warehouse-audit").roles(role))
                    .header("X-Tenant-Id", "default").header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID)
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        }
        String warehouse = "/api/warehouses/" + UUID.randomUUID();
        mvc.perform(put(warehouse).with(user("warehouse-audit").roles(role))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(delete(warehouse).with(user("warehouse-audit").roles(role)))
            .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "MM_MANAGER"})
    void administratorsCanReachReceiptProfileValidation(String role) throws Exception {
        mvc.perform(put("/api/warehouses/" + UUID.randomUUID() + "/receipt-profile")
                .with(user("receipt-profile-editor").roles(role)).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "WAREHOUSE_OPERATOR", "WAREHOUSE_KEEPER", "VIEWER", "AUDITOR", "PROCUREMENT_MANAGER"})
    void warehouseUsersAndReadersCannotEditReceiptProfiles(String role) throws Exception {
        mvc.perform(put("/api/warehouses/" + UUID.randomUUID() + "/receipt-profile")
                .with(user("receipt-profile-reader").roles(role)).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "MM_MANAGER"})
    void administrativeRolesStillReachWarehouseMasterValidation(String role) throws Exception {
        mvc.perform(put("/api/warehouses/" + UUID.randomUUID()).with(user("master-admin").roles(role))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound());
    }
}
