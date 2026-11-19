package com.enterprise.mm.warehouse.reports;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WarehouseReportEndpointTest {
    @Autowired MockMvc mvc;
    @Autowired com.enterprise.mm.warehouse.reports.export.WarehouseExportJobRepository exportJobRepository;

    @Test void warehouseReportsRequireAnAccountingOrWarehouseRole() throws Exception {
        mvc.perform(get("/api/warehouse-reports/options")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/warehouse-reports/options").with(user("department").roles("DEPARTMENT_WORKER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/warehouse-reports/options").with(user("finance").roles("ACCOUNTANT"))).andExpect(status().isOk());
        mvc.perform(get("/api/warehouse-reports/options").with(user("warehouse").roles("WAREHOUSE_KEEPER"))).andExpect(status().isForbidden());
    }

    @Test void overviewUsesWarehouseReadAccessAndApprovalRequiresManagerRole() throws Exception {
        String today=java.time.LocalDate.now().toString();
        mvc.perform(get("/api/warehouse-reports/overview").param("from",today).param("to",today)
            .with(user("auditor").roles("AUDITOR")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.warehouses").isArray());
        mvc.perform(post("/api/warehouse-reports/overview/approve-all").contentType("application/json")
            .content("{\"from\":\""+today+"\",\"to\":\""+today+"\"}")
            .with(user("accountant").roles("ACCOUNTANT"))).andExpect(status().isForbidden());
    }

    @Test void viewAndExportsResolveAgainstTheRealEntitySchema() throws Exception {
        String today=java.time.LocalDate.now().toString();
        mvc.perform(get("/api/warehouse-reports").param("type","MATERIAL_STATEMENT").param("from",today).param("to",today)
            .param("materialSubtype","EQUIPMENT").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.currencyCode").value("UZS"))
            .andExpect(jsonPath("$.data.materialSubtype").value("EQUIPMENT"));
        mvc.perform(get("/api/warehouse-reports/export").param("type","STOCK_BALANCES").param("from",today).param("to",today)
            .param("materialSubtype","ANIMALS").with(user("auditor").roles("AUDITOR"))).andExpect(status().isOk()).andExpect(header().string("Content-Type","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @Test void overviewExcelUsesReportAuthorizationAndPeriodValidation() throws Exception {
        String today=java.time.LocalDate.now().toString();
        mvc.perform(get("/api/warehouse-reports/overview/export").param("from",today).param("to",today))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/warehouse-reports/overview/export").param("from",today).param("to",today)
            .with(user("department").roles("DEPARTMENT_WORKER"))).andExpect(status().isForbidden());
        var response=mvc.perform(get("/api/warehouse-reports/overview/export").param("from",today).param("to",today)
            .param("language","en").with(user("auditor").roles("AUDITOR")))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString(".xlsx")))
            .andReturn().getResponse().getContentAsByteArray();
        try (var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(response))) {
            org.assertj.core.api.Assertions.assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
        }
        mvc.perform(get("/api/warehouse-reports/overview/export").param("from",today).param("to","2000-01-01")
            .with(user("auditor").roles("AUDITOR"))).andExpect(status().isUnprocessableEntity());
    }

    @Test void reportValidationRespondsInRussian() throws Exception {
        String today=java.time.LocalDate.now().toString();
        mvc.perform(get("/api/warehouse-reports").param("type","MATERIAL_STATEMENT").param("from",today).param("to","2000-01-01")
            .header("Accept-Language","ru").with(user("finance").roles("ACCOUNTANT")))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.message").value("Укажите корректный период, не позднее сегодняшней даты."));
        mvc.perform(get("/api/warehouse-reports").param("type","MATERIAL_STATEMENT").param("from",today).param("to",today).param("size","201")
            .header("Accept-Language","ru").with(user("finance").roles("ACCOUNTANT")))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.message").value("Выберите от 1 до 200 строк на странице."));
        mvc.perform(get("/api/warehouse-reports").param("type","STOCK_BALANCES").param("from",today).param("to",today)
            .param("materialType","SERVICES").param("materialSubtype","ANIMALS")
            .header("Accept-Language","ru").with(user("finance").roles("ACCOUNTANT")))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.message").value("Вид ТМЦ можно указать только для товарно-материальных ценностей."));
    }

    @Test void accountFiltersAreLocalizedAndMigrationRequiresAdministrator()throws Exception {
        String today=java.time.LocalDate.now().toString();
        mvc.perform(get("/api/warehouse-reports").param("type","STOCK_BALANCES").param("from",today).param("to",today).param("accountCode","bad")
            .header("Accept-Language","ru").with(user("finance").roles("ACCOUNTANT")))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.message").value("Укажите корректный счёт учёта."));
        mvc.perform(get("/api/warehouse-reports").param("type","STOCK_BALANCES").param("from",today).param("to",today).param("sourceType","BAD")
            .header("Accept-Language","ru").with(user("finance").roles("ACCOUNTANT")))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.message").value("Укажите корректный вид номенклатуры."));
        mvc.perform(get("/api/migration/warehouse-accounts/capabilities").with(user("finance").roles("ACCOUNTANT"))).andExpect(status().isForbidden());
    }

    @Test void fixedAssetsAndServicesBindForViewsAndLocalizedExports() throws Exception {
        String today=java.time.LocalDate.now().toString();
        for (String materialType:java.util.List.of("FIXED_ASSET","SERVICES")) {
            mvc.perform(get("/api/warehouse-reports").param("type","STOCK_BALANCES").param("from",today).param("to",today)
                .param("materialType",materialType).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.materialType").value(materialType));
            mvc.perform(get("/api/warehouse-reports/export").param("type","MATERIAL_STATEMENT").param("from",today).param("to",today)
                .param("materialType",materialType).param("format","csv").param("language","ru").with(user("auditor").roles("AUDITOR")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Группа материалов: "+(materialType.equals("FIXED_ASSET")?"Основные средства":"Услуги"))));
        }
    }

    @Test void referencesEndpointSupportsCounterpartyAndAccount() throws Exception {
        mvc.perform(get("/api/warehouse-reports/references").param("kind","COUNTERPARTY")
            .with(user("accountant").roles("ACCOUNTANT")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isArray());

        mvc.perform(get("/api/warehouse-reports/references").param("kind","ACCOUNT")
            .with(user("accountant").roles("ACCOUNTANT")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isArray());

        mvc.perform(get("/api/warehouse-reports/references").param("kind","UNKNOWN")
            .with(user("accountant").roles("ACCOUNTANT")))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test void materialCardEndpointReturnsChronologicalLedger() throws Exception {
        String today = java.time.LocalDate.now().toString();
        java.util.UUID testMaterialId = java.util.UUID.randomUUID();

        mvc.perform(get("/api/warehouse-reports/material-card")
            .param("materialId", testMaterialId.toString())
            .param("from", today).param("to", today)
            .with(user("accountant").roles("ACCOUNTANT")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.materialId").value(testMaterialId.toString()))
            .andExpect(jsonPath("$.data.entries.content").isArray())
            .andExpect(jsonPath("$.data.openingQuantity").exists())
            .andExpect(jsonPath("$.data.closingQuantity").exists());

        mvc.perform(get("/api/warehouse-reports/material-card")
            .param("materialId", testMaterialId.toString())
            .param("from", today).param("to", "2020-01-01")
            .with(user("accountant").roles("ACCOUNTANT")))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test void groupSummaryAndInventoryComparisonReportsReturnSuccess() throws Exception {
        String today = java.time.LocalDate.now().toString();
        mvc.perform(get("/api/warehouse-reports")
            .param("type", "GROUP_SUMMARY")
            .param("from", today).param("to", today)
            .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.type").value("GROUP_SUMMARY"))
            .andExpect(jsonPath("$.data.content").isArray());

        mvc.perform(get("/api/warehouse-reports")
            .param("type", "INVENTORY_COMPARISON")
            .param("from", today).param("to", today)
            .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.type").value("INVENTORY_COMPARISON"))
            .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test void snapshotTimestampPreservesPointInTimeQueryAndExport() throws Exception {
        String today = java.time.LocalDate.now().toString();
        String snapshot = java.time.LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();

        mvc.perform(get("/api/warehouse-reports")
            .param("type", "STOCK_BALANCES")
            .param("from", today).param("to", today)
            .param("snapshotTimestamp", snapshot)
            .with(user("accountant").roles("ACCOUNTANT")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.type").value("STOCK_BALANCES"));

        mvc.perform(get("/api/warehouse-reports/export")
            .param("type", "STOCK_BALANCES")
            .param("from", today).param("to", today)
            .param("snapshotTimestamp", snapshot)
            .with(user("auditor").roles("AUDITOR")))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @Test void exportJobCanBeSubmittedWithSnapshotTimestamp() throws Exception {
        String today = java.time.LocalDate.now().toString();
        String snapshot = java.time.LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();

        String payload = """
            {
                "reportType": "STOCK_BALANCES",
                "from": "%s",
                "to": "%s",
                "format": "xlsx",
                "language": "ru",
                "snapshotTimestamp": "%s"
            }
            """.formatted(today, today, snapshot);

        mvc.perform(post("/api/warehouse-reports/export-jobs")
            .contentType("application/json")
            .content(payload)
            .with(user("accountant").roles("ACCOUNTANT")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").exists())
            .andExpect(jsonPath("$.data.reportType").value("STOCK_BALANCES"))
            .andExpect(jsonPath("$.data.format").value("xlsx"));
    }

    @Test void exportJobCanBeCancelledViaDeleteOrPost() throws Exception {
        var job = new com.enterprise.mm.warehouse.reports.export.WarehouseExportJob();
        job.setId(java.util.UUID.randomUUID());
        job.setTenantId(com.enterprise.mm.common.TenantContext.currentTenant());
        job.setCompanyId(com.enterprise.mm.common.CompanyContext.currentCompany());
        job.setReportType(WarehouseReport.Type.STOCK_BALANCES);
        job.setStatus(com.enterprise.mm.warehouse.reports.export.WarehouseExportJobStatus.PROCESSING);
        job.setProgressPercent(20);
        job.setCreatedAt(java.time.LocalDateTime.now());
        exportJobRepository.save(job);

        // Cancel via DELETE
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/warehouse-reports/export-jobs/" + job.getId())
            .with(user("accountant").roles("ACCOUNTANT")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        // Idempotent cancel via POST /cancel returns CANCELLED
        mvc.perform(post("/api/warehouse-reports/export-jobs/" + job.getId() + "/cancel")
            .with(user("accountant").roles("ACCOUNTANT")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        // Completed job rejection
        var completedJob = new com.enterprise.mm.warehouse.reports.export.WarehouseExportJob();
        completedJob.setId(java.util.UUID.randomUUID());
        completedJob.setTenantId(com.enterprise.mm.common.TenantContext.currentTenant());
        completedJob.setCompanyId(com.enterprise.mm.common.CompanyContext.currentCompany());
        completedJob.setReportType(WarehouseReport.Type.STOCK_BALANCES);
        completedJob.setStatus(com.enterprise.mm.warehouse.reports.export.WarehouseExportJobStatus.COMPLETED);
        completedJob.setProgressPercent(100);
        completedJob.setCreatedAt(java.time.LocalDateTime.now());
        exportJobRepository.save(completedJob);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/warehouse-reports/export-jobs/" + completedJob.getId())
            .with(user("accountant").roles("ACCOUNTANT")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("terminal state")));
    }
}
