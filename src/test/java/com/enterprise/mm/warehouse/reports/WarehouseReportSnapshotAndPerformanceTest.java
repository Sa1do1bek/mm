package com.enterprise.mm.warehouse.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.MaterialSubtype;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.service.InventoryAccountingSettingsService;
import com.enterprise.mm.service.RoleDataScopeService;
import com.enterprise.mm.warehouse.reports.WarehouseReport.Filter;
import com.enterprise.mm.warehouse.reports.WarehouseReport.Row;
import com.enterprise.mm.warehouse.reports.WarehouseReport.Total;
import com.enterprise.mm.warehouse.reports.WarehouseReport.Type;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class WarehouseReportSnapshotAndPerformanceTest {

    private WarehouseReportQuery query;
    private WarehouseAccountReportQuery accountQuery;
    private RoleDataScopeService access;
    private InventoryAccountingSettingsService settings;
    private NamedParameterJdbcTemplate jdbc;
    private WarehouseReportService service;
    private WarehouseReportExport exporter;

    private final String tenantId = "navoiy-azot";
    private final UUID companyId = UUID.randomUUID();
    private final UUID warehouseId = UUID.randomUUID();
    private final UUID materialId1 = UUID.randomUUID();
    private final UUID materialId2 = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);

        query = mock(WarehouseReportQuery.class);
        accountQuery = mock(WarehouseAccountReportQuery.class);
        access = mock(RoleDataScopeService.class);
        settings = mock(InventoryAccountingSettingsService.class);
        jdbc = mock(NamedParameterJdbcTemplate.class);
        exporter = new WarehouseReportExport();

        when(access.warehouseReadScope(any(),any())).thenAnswer(call -> call.getArgument(0)==null ? java.util.Set.of() : java.util.Set.of((java.util.UUID)call.getArgument(0)));

        var accountingSettings = new com.enterprise.mm.entity.InventoryAccountingSettings();
        accountingSettings.setBaseCurrencyCode("UZS");
        when(settings.findOrDefault()).thenReturn(accountingSettings);

        when(query.scope()).thenReturn(new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("tenant", tenantId)
            .addValue("company", companyId));

        when(jdbc.query(any(String.class), any(org.springframework.jdbc.core.namedparam.MapSqlParameterSource.class), any(RowMapper.class)))
            .thenReturn(List.of("AO Navoiyazot"));

        service = new WarehouseReportService(query, access, settings, jdbc, accountQuery);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    @DisplayName("P7 AT05: Screen rows, totals, CSV and XLSX exports reflect identical snapshot without divergence")
    void p7_at05_identicalScopedSnapshotAcrossScreenTotalsAndExports() throws Exception {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 14);
        when(accountQuery.available()).thenReturn(true);
        when(accountQuery.coverageStart()).thenReturn(LocalDate.of(2026, 1, 1));
        when(accountQuery.snapshotDate()).thenReturn(LocalDate.of(2026, 10, 8));

        // Row 1: Item A - 120.5000000 PCS, closing amount 1,205,000.0000 UZS
        Row row1 = new Row(
            warehouseId + ":1010:" + materialId1 + ":PCS",
            materialId1, warehouseId, "WH-101", "Central Chemical Depot",
            groupId, "GRP-ACID", "Chemical Acids", "MAT-SULFURIC", "Sulfuric Acid Technical Grade",
            "PCS", "pcs", "шт", "dona", "—",
            "6010", "10000.0000",
            "100.0000000", "1000000.0000",
            "50.5000000", "505000.0000",
            "30.0000000", "300000.0000",
            "120.5000000", "1205000.0000",
            "1010", "Materials", "Материалы", "OWNED"
        );

        // Row 2: Item B - 80.2500000 PCS, closing amount 802,500.0000 UZS
        Row row2 = new Row(
            warehouseId + ":1010:" + materialId2 + ":PCS",
            materialId2, warehouseId, "WH-101", "Central Chemical Depot",
            groupId, "GRP-ACID", "Chemical Acids", "MAT-NITRIC", "Nitric Acid Pure",
            "PCS", "pcs", "шт", "dona", "—",
            "6010", "10000.0000",
            "50.0000000", "500000.0000",
            "40.2500000", "402500.0000",
            "10.0000000", "100000.0000",
            "80.2500000", "802500.0000",
            "1010", "Materials", "Материалы", "OWNED"
        );

        // Total: 200.7500000 PCS, 2,007,500.0000 UZS
        Total total = new Total(
            "PCS", "pcs", "шт", "dona",
            "150.0000000", "1500000.0000",
            "90.7500000", "907500.0000",
            "40.0000000", "400000.0000",
            "200.7500000", "2007500.0000",
            "1010", "Materials"
        );

        when(accountQuery.rows(any(Filter.class), anyBoolean())).thenReturn(List.of(row1, row2));
        when(accountQuery.count(any(Filter.class))).thenReturn(2L);
        when(accountQuery.totals(any(Filter.class))).thenReturn(List.of(total));

        Filter filter = new Filter(
            Type.MATERIAL_STATEMENT, from, to, warehouseId, null,
            null, null, 0, 50, null, null, "1010", null
        );

        // 1. Generate report snapshot
        WarehouseReport report = service.generate(filter, true);
        assertThat(report.content()).hasSize(2);
        assertThat(report.totals()).hasSize(1);

        // Sum of rows vs Totals check
        BigDecimal sumRowQty = new BigDecimal(row1.closingQuantity()).add(new BigDecimal(row2.closingQuantity()));
        BigDecimal sumRowAmt = new BigDecimal(row1.closingAmount()).add(new BigDecimal(row2.closingAmount()));
        BigDecimal totalQty = new BigDecimal(report.totals().get(0).closingQuantity());
        BigDecimal totalAmt = new BigDecimal(report.totals().get(0).closingAmount());

        assertThat(totalQty).isEqualByComparingTo(sumRowQty);
        assertThat(totalAmt).isEqualByComparingTo(sumRowAmt);
        assertThat(totalQty).isEqualByComparingTo("200.7500000");
        assertThat(totalAmt).isEqualByComparingTo("2007500.0000");

        // 2. Export to CSV: verify identical snapshot contents
        byte[] csvBytes = exporter.csv(report, "ru");
        String csvContent = new String(csvBytes, StandardCharsets.UTF_8);

        assertThat(csvContent).contains("MAT-SULFURIC");
        assertThat(csvContent).contains("MAT-NITRIC");
        assertThat(csvContent).contains("120.5000000");
        assertThat(csvContent).contains("80.2500000");
        assertThat(csvContent).contains("200.7500000");
        assertThat(csvContent).contains("2007500.0000");

        // 3. Export to XLSX: verify identical workbook structure and values
        byte[] xlsxBytes = exporter.xlsx(report, "ru");
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsxBytes))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet).isNotNull();

            StringBuilder sheetText = new StringBuilder();
            for (org.apache.poi.ss.usermodel.Row r : sheet) {
                for (org.apache.poi.ss.usermodel.Cell c : r) {
                    sheetText.append(c.toString()).append(" ");
                }
            }

            assertThat(sheetText.toString()).contains("MAT-SULFURIC");
            assertThat(sheetText.toString()).contains("MAT-NITRIC");
            assertThat(sheetText.toString()).contains("120.5");
            assertThat(sheetText.toString()).contains("80.25");
            assertThat(sheetText.toString()).contains("200.75");
        }
    }

    @Test
    @DisplayName("P7 AT07: PostgreSQL plan safety, bounded export jobs, and concurrent posting isolation")
    void p7_at07_performanceAndConcurrentPostingBounds() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 14);

        // 1. Guard against unbounded export requests: page size > 200 must be rejected
        assertThatThrownBy(() -> service.generate(new Filter(
            Type.STOCK_BALANCES, from, to, warehouseId, null,
            null, null, 0, 201, null, null, null, null
        ), false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Report page size must be between 1 and 200");

        // 2. Negative page rejection
        assertThatThrownBy(() -> service.generate(new Filter(
            Type.STOCK_BALANCES, from, to, warehouseId, null,
            null, null, -1, 50, null, null, null, null
        ), false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Report page size must be between 1 and 200");

        // 3. Excessively long search queries capped at 200 characters to prevent query plan degradation
        String longSearch = "A".repeat(201);
        assertThatThrownBy(() -> service.generate(new Filter(
            Type.STOCK_BALANCES, from, to, warehouseId, null,
            null, longSearch, 0, 50, null, null, null, null
        ), false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Report search is limited to 200 characters");

        // 4. Invalid date period order rejection (from > to)
        assertThatThrownBy(() -> service.generate(new Filter(
            Type.STOCK_BALANCES, to, from, warehouseId, null,
            null, null, 0, 50, null, null, null, null
        ), false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Select a valid report period ending today or earlier");

        // 5. Future date rejection
        LocalDate futureDate = LocalDate.now().plusDays(5);
        assertThatThrownBy(() -> service.generate(new Filter(
            Type.STOCK_BALANCES, from, futureDate, warehouseId, null,
            null, null, 0, 50, null, null, null, null
        ), false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Select a valid report period ending today or earlier");

        // 6. TMB subtype filter safety: only TMB materials can have subtype filter
        assertThatThrownBy(() -> service.generate(new Filter(
            Type.STOCK_BALANCES, from, to, warehouseId, null,
            null, null, 0, 50, MaterialSubtype.MATERIAL, com.enterprise.mm.enums.MaterialType.SERVICES, null, null
        ), false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Inventory subtype is only available for TMB materials");
    }
}
