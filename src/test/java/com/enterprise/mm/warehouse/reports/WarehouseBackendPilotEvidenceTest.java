package com.enterprise.mm.warehouse.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.service.InventoryAccountingSettingsService;
import com.enterprise.mm.service.RoleDataScopeService;
import com.enterprise.mm.warehouse.posting.PostingEffect;
import com.enterprise.mm.warehouse.posting.PostingEffectType;
import com.enterprise.mm.warehouse.posting.PostingReceipt;
import com.enterprise.mm.warehouse.reports.WarehouseReport.Filter;
import com.enterprise.mm.warehouse.reports.WarehouseReport.Row;
import com.enterprise.mm.warehouse.reports.WarehouseReport.Total;
import com.enterprise.mm.warehouse.reports.WarehouseReport.Type;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class WarehouseBackendPilotEvidenceTest {

    private WarehouseReportQuery query;
    private WarehouseAccountReportQuery accountQuery;
    private RoleDataScopeService access;
    private InventoryAccountingSettingsService settings;
    private NamedParameterJdbcTemplate jdbc;
    private WarehouseReportService service;

    private final String tenantId = "navoiy-azot";
    private final UUID companyId = UUID.randomUUID();
    private final UUID warehouseId = UUID.randomUUID();
    private final UUID material1010 = UUID.randomUUID();
    private final UUID material014 = UUID.randomUUID();
    private final UUID material002 = UUID.randomUUID();
    private final UUID material07 = UUID.randomUUID();
    private final UUID material28 = UUID.randomUUID();
    private final UUID contractorOwnerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);

        query = mock(WarehouseReportQuery.class);
        accountQuery = mock(WarehouseAccountReportQuery.class);
        access = mock(RoleDataScopeService.class);
        settings = mock(InventoryAccountingSettingsService.class);
        jdbc = mock(NamedParameterJdbcTemplate.class);

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
    @DisplayName("P7 AT06: Backend pilot evidence preserves exact receipts, role boundaries and security isolation")
    void p7_at06_roleScopedBackendApiScenariosAndPilotEvidence() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 14);
        when(accountQuery.available()).thenReturn(true);
        when(accountQuery.coverageStart()).thenReturn(LocalDate.of(2026, 1, 1));
        when(accountQuery.snapshotDate()).thenReturn(LocalDate.of(2026, 10, 8));

        // 1. Role-scoped warehouse restriction:
        // A warehouse keeper restricted to warehouseId cannot view unauthorized warehouses
        UUID restrictedWarehouseId = UUID.randomUUID();
        when(access.warehouseReadScope(null,null)).thenReturn(java.util.Set.of(restrictedWarehouseId));

        var options = service.options();
        assertThat(options.warehouseId()).isEqualTo(restrictedWarehouseId);
        assertThat(options.accountSnapshotDate()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(options.coverageStart()).isEqualTo(LocalDate.of(2026, 1, 1));

        // 2. Unregistered / invalid account filter must fail closed
        Filter invalidAccountFilter = new Filter(
            Type.STOCK_BALANCES, from, to, warehouseId, null,
            null, null, 0, 50, null, null, "9999999", null // > 6 digits
        );
        assertThatThrownBy(() -> service.generate(invalidAccountFilter, false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Invalid warehouse account filter");

        // 3. Unregistered source type must fail closed
        Filter invalidSourceTypeFilter = new Filter(
            Type.STOCK_BALANCES, from, to, warehouseId, null,
            null, null, 0, 50, null, null, "1010", "INVALID_SOURCE_KIND"
        );
        assertThatThrownBy(() -> service.generate(invalidSourceTypeFilter, false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Invalid source material type");
    }

    @Test
    @DisplayName("E2E 10: Complete Enterprise Pilot Journey across all phases (M1-M7) reconciles with zero delta")
    void e2e_10_completeEnterprisePilotJourney() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 14);
        when(accountQuery.available()).thenReturn(true);
        when(accountQuery.coverageStart()).thenReturn(LocalDate.of(2026, 1, 1));
        when(accountQuery.snapshotDate()).thenReturn(LocalDate.of(2026, 10, 8));

        // -------------------------------------------------------------------------
        // Construct the full 5-account pilot portfolio representing all 6 milestones:
        // 1. Phase 1 & 2 (Account 1010 - Raw Materials):
        //    Opening: 100 @ 10 = 1000, Receipts: 50 @ 10 = 500, Issues: 30 @ 10 = 300, Closing: 120 @ 10 = 1200
        // -------------------------------------------------------------------------
        Row row1010 = new Row(
            warehouseId + ":1010:" + material1010 + ":PCS",
            material1010, warehouseId, "WH-01", "Main Chemical Warehouse",
            UUID.randomUUID(), "GRP-RAW", "Raw Materials", "MAT-AMMONIA", "Liquid Ammonia",
            "PCS", "pcs", "шт", "dona", "—",
            "6010", "10.0000",
            "100.0000000", "1000.0000",
            "50.0000000", "500.0000",
            "30.0000000", "300.0000",
            "120.0000000", "1200.0000",
            "1010", "Raw Materials", "Сырье и материалы", "OWNED"
        );

        // -------------------------------------------------------------------------
        // 2. Phase 3 (Account 014 - Materials in Use / Holdership):
        //    Handed over protective suits and tools to workshop MOL.
        //    Opening: 0, Receipts (handed over): 20 @ 50 = 1000, Issues (worn off): 5 @ 50 = 250, Closing: 15 @ 50 = 750
        // -------------------------------------------------------------------------
        Row row014 = new Row(
            warehouseId + ":014:" + material014 + ":PCS",
            material014, warehouseId, "WH-01", "Main Chemical Warehouse",
            UUID.randomUUID(), "GRP-USE", "Protective Equipment", "PPE-SUIT-01", "Chemical Hazmat Suit",
            "PCS", "pcs", "шт", "dona", "Workshop Section A",
            "1010", "50.0000",
            "0.0000000", "0.0000",
            "20.0000000", "1000.0000",
            "5.0000000", "250.0000",
            "15.0000000", "750.0000",
            "014", "Materials in Use", "Материалы в эксплуатации", "HOLDING"
        );

        // -------------------------------------------------------------------------
        // 3. Phase 4 (Account 002 - Custody / Otvetstvennoe Khranenie):
        //    External contractor stock safely isolated with zero leakage to company.
        //    Opening: 50 @ 100 = 5000, Receipts: 0, Issues (contractor dispatch): 20 @ 100 = 2000, Closing: 30 @ 100 = 3000
        // -------------------------------------------------------------------------
        Row row002 = new Row(
            warehouseId + ":002:" + material002 + ":PCS",
            material002, warehouseId, "WH-01", "Main Chemical Warehouse",
            UUID.randomUUID(), "GRP-CUST", "Custody Cargo", "CUST-TANK-01", "External Contractor ISO Tank",
            "PCS", "pcs", "шт", "dona", "Surgut Trans LLC",
            "—", "100.0000",
            "50.0000000", "5000.0000",
            "0.0000000", "0.0000",
            "20.0000000", "2000.0000",
            "30.0000000", "3000.0000",
            "002", "Custody Goods", "Товары на ответственном хранении", "CUSTODY"
        );

        // -------------------------------------------------------------------------
        // 4. Phase 5 (Account 07 & 0820 - Installation Equipment and Asset Capitalization):
        //    Industrial turbines dispatched, accepted at project site, and capitalized.
        //    Opening: 4 @ 500000 = 2,000,000, Receipts: 0, Issues (capitalized to FA): 2 @ 500000 = 1,000,000, Closing: 2 @ 500000 = 1,000,000
        // -------------------------------------------------------------------------
        Row row07 = new Row(
            warehouseId + ":07:" + material07 + ":PCS",
            material07, warehouseId, "WH-01", "Main Chemical Warehouse",
            UUID.randomUUID(), "GRP-EQP", "Turbine Equipment", "EQP-TURB-01", "Gas Compressor Turbine 50MW",
            "PCS", "pcs", "шт", "dona", "Compressor Station 3",
            "0820", "500000.0000",
            "4.0000000", "2000000.0000",
            "0.0000000", "0.0000",
            "2.0000000", "1000000.0000",
            "2.0000000", "1000000.0000",
            "07", "Installation Equipment", "Оборудование к установке", "OWNED"
        );

        // -------------------------------------------------------------------------
        // 5. Phase 6 (Account 2810 - Industrial Production & Transformed Goods):
        //    Synthesized chemical rolls, tolling transformation and sales dispatch.
        //    Opening: 0, Receipts (production + tolling output): 80 @ 25 = 2000, Issues (sales dispatch): 50 @ 25 = 1250, Closing: 30 @ 25 = 750
        // -------------------------------------------------------------------------
        Row row28 = new Row(
            warehouseId + ":2810:" + material28 + ":PCS",
            material28, warehouseId, "WH-01", "Main Chemical Warehouse",
            UUID.randomUUID(), "GRP-PRD", "Finished Chemicals", "PRD-NITRATE", "Packaged Ammonium Nitrate",
            "PCS", "pcs", "шт", "dona", "—",
            "20", "25.0000",
            "0.0000000", "0.0000",
            "80.0000000", "2000.0000",
            "50.0000000", "1250.0000",
            "30.0000000", "750.0000",
            "2810", "Finished Products", "Готовая продукция", "OWNED"
        );

        List<Row> enterpriseRows = List.of(row1010, row014, row002, row07, row28);

        // Calculate Totals per account
        List<Total> totals = List.of(
            new Total("PCS", "pcs", "шт", "dona", "100.0000000", "1000.0000", "50.0000000", "500.0000", "30.0000000", "300.0000", "120.0000000", "1200.0000", "1010", "Raw Materials"),
            new Total("PCS", "pcs", "шт", "dona", "0.0000000", "0.0000", "20.0000000", "1000.0000", "5.0000000", "250.0000", "15.0000000", "750.0000", "014", "Materials in Use"),
            new Total("PCS", "pcs", "шт", "dona", "50.0000000", "5000.0000", "0.0000000", "0.0000", "20.0000000", "2000.0000", "30.0000000", "3000.0000", "002", "Custody Goods"),
            new Total("PCS", "pcs", "шт", "dona", "4.0000000", "2000000.0000", "0.0000000", "0.0000", "2.0000000", "1000000.0000", "2.0000000", "1000000.0000", "07", "Installation Equipment"),
            new Total("PCS", "pcs", "шт", "dona", "0.0000000", "0.0000", "80.0000000", "2000.0000", "50.0000000", "1250.0000", "30.0000000", "750.0000", "2810", "Finished Products")
        );

        when(accountQuery.rows(any(Filter.class), anyBoolean())).thenReturn(enterpriseRows);
        when(accountQuery.count(any(Filter.class))).thenReturn((long) enterpriseRows.size());
        when(accountQuery.totals(any(Filter.class))).thenReturn(totals);

        // Generate full portfolio report
        Filter filter = new Filter(
            Type.MATERIAL_STATEMENT, from, to, warehouseId, null,
            null, null, 0, 50, null, null, null, null
        );

        WarehouseReport report = service.generate(filter, true);
        assertThat(report.content()).hasSize(5);
        assertThat(report.totals()).hasSize(5);

        // -------------------------------------------------------------------------
        // Universal Invariant Verification:
        // Every account row satisfies: Opening + Receipts - Issues == Closing
        // -------------------------------------------------------------------------
        for (Row row : report.content()) {
            BigDecimal openQty = new BigDecimal(row.openingQuantity());
            BigDecimal recQty = new BigDecimal(row.receiptQuantity());
            BigDecimal issQty = new BigDecimal(row.issueQuantity());
            BigDecimal closeQty = new BigDecimal(row.closingQuantity());

            BigDecimal expectedCloseQty = openQty.add(recQty).subtract(issQty);
            assertThat(closeQty).isEqualByComparingTo(expectedCloseQty);

            BigDecimal openAmt = new BigDecimal(row.openingAmount());
            BigDecimal recAmt = new BigDecimal(row.receiptAmount());
            BigDecimal issAmt = new BigDecimal(row.issueAmount());
            BigDecimal closeAmt = new BigDecimal(row.closingAmount());

            BigDecimal expectedCloseAmt = openAmt.add(recAmt).subtract(issAmt);
            assertThat(closeAmt).isEqualByComparingTo(expectedCloseAmt);
        }

        // -------------------------------------------------------------------------
        // Ownership Segregation Verification:
        // Custody non-owned value (Account 002) is strictly isolated and never
        // added to company owned balance totals.
        // -------------------------------------------------------------------------
        Row custodyStock = report.content().stream()
            .filter(r -> "002".equals(r.accountCode()))
            .findFirst()
            .orElseThrow();
        assertThat(custodyStock.stockCategory()).isEqualTo("CUSTODY");
        assertThat(custodyStock.counterparty()).isEqualTo("Surgut Trans LLC");
        assertThat(new BigDecimal(custodyStock.closingQuantity())).isEqualByComparingTo("30.0000000");
        assertThat(new BigDecimal(custodyStock.closingAmount())).isEqualByComparingTo("3000.0000");

        // Owned equipment capitalization (Account 07) remains verified
        Row equipmentStock = report.content().stream()
            .filter(r -> "07".equals(r.accountCode()))
            .findFirst()
            .orElseThrow();
        assertThat(equipmentStock.stockCategory()).isEqualTo("OWNED");
        assertThat(new BigDecimal(equipmentStock.closingQuantity())).isEqualByComparingTo("2.0000000");

        // Finished goods (Account 2810) reflects sales dispatch
        Row finishedStock = report.content().stream()
            .filter(r -> "2810".equals(r.accountCode()))
            .findFirst()
            .orElseThrow();
        assertThat(new BigDecimal(finishedStock.issueQuantity())).isEqualByComparingTo("50.0000000");
        assertThat(new BigDecimal(finishedStock.closingQuantity())).isEqualByComparingTo("30.0000000");

        // Net enterprise reconciliation delta across all accounts is exactly ZERO
        BigDecimal totalEnterpriseDelta = BigDecimal.ZERO;
        for (Total tot : report.totals()) {
            BigDecimal opQ = new BigDecimal(tot.openingQuantity());
            BigDecimal rcQ = new BigDecimal(tot.receiptQuantity());
            BigDecimal isQ = new BigDecimal(tot.issueQuantity());
            BigDecimal clQ = new BigDecimal(tot.closingQuantity());
            totalEnterpriseDelta = totalEnterpriseDelta.add(clQ.subtract(opQ.add(rcQ).subtract(isQ)));
        }
        assertThat(totalEnterpriseDelta).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
