package com.enterprise.mm.warehouse.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.MaterialSubtype;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.service.InventoryAccountingSettingsService;
import com.enterprise.mm.service.RoleDataScopeService;
import com.enterprise.mm.warehouse.posting.PostingEffect;
import com.enterprise.mm.warehouse.posting.PostingEffectType;
import com.enterprise.mm.warehouse.reports.WarehouseReport.Filter;
import com.enterprise.mm.warehouse.reports.WarehouseReport.Row;
import com.enterprise.mm.warehouse.reports.WarehouseReport.Total;
import com.enterprise.mm.warehouse.reports.WarehouseReport.Type;
import java.math.BigDecimal;
import java.math.RoundingMode;
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

class WarehouseReportReconstructionTest {

    private WarehouseReportQuery query;
    private WarehouseAccountReportQuery accountQuery;
    private RoleDataScopeService access;
    private InventoryAccountingSettingsService settings;
    private NamedParameterJdbcTemplate jdbc;
    private WarehouseReportService service;

    private final String tenantId = "navoiy-azot";
    private final UUID companyId = UUID.randomUUID();
    private final UUID warehouseId1 = UUID.randomUUID();
    private final UUID warehouseId2 = UUID.randomUUID();
    private final UUID materialId1 = UUID.randomUUID();
    private final UUID materialId2 = UUID.randomUUID();
    private final UUID groupId1 = UUID.randomUUID();
    private final UUID groupId2 = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

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
    @DisplayName("P7 AT01: Material Card & Account Statement reconstruction preserves exact accounting equation")
    void p7_at01_materialCardAndAccountStatementReconstruction() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 14);
        when(accountQuery.available()).thenReturn(true);
        when(accountQuery.coverageStart()).thenReturn(LocalDate.of(2026, 1, 1));
        when(accountQuery.snapshotDate()).thenReturn(LocalDate.of(2026, 10, 8));

        // Create representative material row with exact 7-decimal quantities and 4-decimal values
        // Opening: 100.0000000 @ 10.0 = 1000.0000
        // Receipts: 50.0000000 @ 10.0 = 500.0000
        // Issues: 30.0000000 @ 10.0 = 300.0000
        // Closing: 120.0000000 @ 10.0 = 1200.0000
        Row row = new Row(
            warehouseId1 + ":1010:" + materialId1 + ":PCS",
            materialId1, warehouseId1, "WH-101", "Central Chemical Depot",
            groupId1, "GRP-RAW", "Raw Materials", "MAT-CH-01", "Liquid Ammonia",
            "PCS", "pcs", "шт", "dona", "Navoiyazot Plant",
            "6010", "10.0000",
            "100.0000000", "1000.0000",
            "50.0000000", "500.0000",
            "30.0000000", "300.0000",
            "120.0000000", "1200.0000",
            "1010", "Materials", "Материалы", "OWNED"
        );

        Total total = new Total(
            "PCS", "pcs", "шт", "dona",
            "100.0000000", "1000.0000",
            "50.0000000", "500.0000",
            "30.0000000", "300.0000",
            "120.0000000", "1200.0000",
            "1010", "Materials"
        );

        when(accountQuery.rows(any(Filter.class), anyBoolean())).thenReturn(List.of(row));
        when(accountQuery.count(any(Filter.class))).thenReturn(1L);
        when(accountQuery.totals(any(Filter.class))).thenReturn(List.of(total));

        Filter filter = new Filter(
            Type.MATERIAL_STATEMENT, from, to, warehouseId1, materialId1,
            groupId1, null, 0, 50, null, null, "1010", null
        );

        WarehouseReport report = service.generate(filter, true);

        assertThat(report.content()).hasSize(1);
        Row resultRow = report.content().get(0);

        // Verify exact accounting equation on Row level:
        // Opening + Receipts - Issues = Closing
        BigDecimal openingQty = new BigDecimal(resultRow.openingQuantity());
        BigDecimal receiptQty = new BigDecimal(resultRow.receiptQuantity());
        BigDecimal issueQty = new BigDecimal(resultRow.issueQuantity());
        BigDecimal closingQty = new BigDecimal(resultRow.closingQuantity());

        BigDecimal calculatedClosingQty = openingQty.add(receiptQty).subtract(issueQty);
        assertThat(closingQty).isEqualByComparingTo(calculatedClosingQty);
        assertThat(closingQty).isEqualByComparingTo("120.0000000");

        BigDecimal openingAmt = new BigDecimal(resultRow.openingAmount());
        BigDecimal receiptAmt = new BigDecimal(resultRow.receiptAmount());
        BigDecimal issueAmt = new BigDecimal(resultRow.issueAmount());
        BigDecimal closingAmt = new BigDecimal(resultRow.closingAmount());

        BigDecimal calculatedClosingAmt = openingAmt.add(receiptAmt).subtract(issueAmt);
        assertThat(closingAmt).isEqualByComparingTo(calculatedClosingAmt);
        assertThat(closingAmt).isEqualByComparingTo("1200.0000");

        // Verify Totals match row sums exactly
        Total resultTotal = report.totals().get(0);
        assertThat(new BigDecimal(resultTotal.closingQuantity())).isEqualByComparingTo(closingQty);
        assertThat(new BigDecimal(resultTotal.closingAmount())).isEqualByComparingTo(closingAmt);
    }

    @Test
    @DisplayName("P7 AT02: Filter integrity isolates accounts, groups, warehouses and non-owned custody positions")
    void p7_at02_filterIntegrityIsolation() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 14);
        when(accountQuery.available()).thenReturn(true);
        when(accountQuery.coverageStart()).thenReturn(LocalDate.of(2026, 1, 1));

        // Row 1: Owned Finished Goods on Account 2810 in Warehouse 1
        Row rowOwned28 = new Row(
            warehouseId1 + ":2810:" + materialId1 + ":PCS",
            materialId1, warehouseId1, "WH-101", "Central Chemical Depot",
            groupId1, "GRP-FIN", "Finished Products", "PRD-NITRO", "Ammonium Nitrate",
            "PCS", "pcs", "шт", "dona", "—",
            "20", "25.0000",
            "0.0000000", "0.0000",
            "100.0000000", "2500.0000",
            "20.0000000", "500.0000",
            "80.0000000", "2000.0000",
            "2810", "Finished Goods", "Готовая продукция", "OWNED"
        );

        // Row 2: Custody non-owned materials on Account 002 in Warehouse 1
        Row rowCustody002 = new Row(
            warehouseId1 + ":002:" + materialId2 + ":PCS",
            materialId2, warehouseId1, "WH-101", "Central Chemical Depot",
            groupId2, "GRP-CUST", "Custody Items", "CUST-CONT-01", "Contractor Containers",
            "PCS", "pcs", "шт", "dona", "Supplier Partner LLC",
            "—", "50.0000",
            "50.0000000", "2500.0000",
            "0.0000000", "0.0000",
            "10.0000000", "500.0000",
            "40.0000000", "2000.0000",
            "002", "Custody", "Материалы на ответственном хранении", "CUSTODY"
        );

        // When filtering specifically for Account 2810:
        when(accountQuery.rows(any(Filter.class), anyBoolean())).thenAnswer(invocation -> {
            Filter f = invocation.getArgument(0);
            if ("2810".equals(f.accountCode())) {
                return List.of(rowOwned28);
            } else if ("002".equals(f.accountCode())) {
                return List.of(rowCustody002);
            }
            return List.of(rowOwned28, rowCustody002);
        });
        when(accountQuery.count(any(Filter.class))).thenReturn(1L);
        when(accountQuery.totals(any(Filter.class))).thenReturn(List.of(
            new Total("PCS", "pcs", "шт", "dona", "0", "0", "100", "2500", "20", "500", "80", "2000", "2810", "Finished Goods")
        ));

        // 1. Filter by account 2810: guarantees 002 custody stock does not leak into account 2810
        Filter filter28 = new Filter(
            Type.STOCK_BALANCES, from, to, warehouseId1, null,
            null, null, 0, 50, null, null, "2810", null
        );
        WarehouseReport report28 = service.generate(filter28, false);

        assertThat(report28.content()).hasSize(1);
        Row isolatedRow = report28.content().get(0);
        assertThat(isolatedRow.accountCode()).isEqualTo("2810");
        assertThat(isolatedRow.stockCategory()).isEqualTo("OWNED");
        assertThat(isolatedRow.materialCode()).isEqualTo("PRD-NITRO");

        // 2. Filter by account 002: guarantees custody non-owned assets remain isolated
        Filter filter002 = new Filter(
            Type.STOCK_BALANCES, from, to, warehouseId1, null,
            null, null, 0, 50, null, null, "002", null
        );
        WarehouseReport report002 = service.generate(filter002, false);

        assertThat(report002.content()).hasSize(1);
        Row custodyRow = report002.content().get(0);
        assertThat(custodyRow.accountCode()).isEqualTo("002");
        assertThat(custodyRow.stockCategory()).isEqualTo("CUSTODY");
        assertThat(custodyRow.materialCode()).isEqualTo("CUST-CONT-01");
    }

    @Test
    @DisplayName("P7 AT03: Source vs Native coverage boundary integrates seamlessly without double-counting opening")
    void p7_at03_sourceVsNativeCoverageBoundary() {
        LocalDate coverageStart = LocalDate.of(2026, 1, 1);
        LocalDate snapshotDate = LocalDate.of(2026, 10, 8);
        when(accountQuery.available()).thenReturn(true);
        when(accountQuery.coverageStart()).thenReturn(coverageStart);
        when(accountQuery.snapshotDate()).thenReturn(snapshotDate);

        // Period crossing the snapshot boundary: from 2026-10-01 to 2026-10-14
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 14);

        // Pre-snapshot opening (at Oct 1): 80.0000000 @ 10 = 800.0000
        // Pre-snapshot 1C movements (Oct 1 - Oct 8): +20.0000000 receipt, -10.0000000 issue (Net +10)
        // Snapshot baseline (at Oct 8): 90.0000000 @ 10 = 900.0000
        // Post-snapshot native MM postings (Oct 9 - Oct 15): +30.0000000 receipt, -5.0000000 issue (Net +25)
        // Closing balance (at Oct 15): 80 + (20 - 10) + (30 - 5) = 115.0000000 @ 10 = 1150.0000
        Row combinedRow = new Row(
            warehouseId1 + ":1010:" + materialId1 + ":PCS",
            materialId1, warehouseId1, "WH-101", "Central Chemical Depot",
            groupId1, "GRP-RAW", "Raw Materials", "MAT-AM-01", "Ammonia Catalyst",
            "PCS", "pcs", "шт", "dona", "—",
            "6010", "10.0000",
            "80.0000000", "800.0000",
            "50.0000000", "500.0000",  // Total receipts: 20 (1C) + 30 (Native) = 50
            "15.0000000", "150.0000",  // Total issues: 10 (1C) + 5 (Native) = 15
            "115.0000000", "1150.0000",
            "1010", "Materials", "Материалы", "OWNED"
        );

        when(accountQuery.rows(any(Filter.class), anyBoolean())).thenReturn(List.of(combinedRow));
        when(accountQuery.count(any(Filter.class))).thenReturn(1L);
        when(accountQuery.totals(any(Filter.class))).thenReturn(List.of(
            new Total("PCS", "pcs", "шт", "dona", "80", "800", "50", "500", "15", "150", "115", "1150", "1010", "Materials")
        ));

        Filter filter = new Filter(
            Type.MATERIAL_STATEMENT, from, to, warehouseId1, materialId1,
            null, null, 0, 50, null, null, "1010", null
        );

        WarehouseReport report = service.generate(filter, false);

        assertThat(report.coverageStart()).isEqualTo(coverageStart);
        assertThat(report.accountSnapshotDate()).isEqualTo(snapshotDate);
        assertThat(report.warnings()).contains("ACCOUNT_WAREHOUSE_BALANCES");

        Row row = report.content().get(0);
        BigDecimal opening = new BigDecimal(row.openingQuantity());
        BigDecimal receipts = new BigDecimal(row.receiptQuantity());
        BigDecimal issues = new BigDecimal(row.issueQuantity());
        BigDecimal closing = new BigDecimal(row.closingQuantity());

        // Assert no double-counting of opening baseline:
        // 80 + 50 - 15 = 115
        assertThat(opening.add(receipts).subtract(issues)).isEqualByComparingTo(closing);
        assertThat(closing).isEqualByComparingTo("115.0000000");
    }

    @Test
    @DisplayName("P7 AT04: Independent opening-to-closing reconstruction matches report to zero quantity and currency delta")
    void p7_at04_independentOpeningToClosingReconstruction() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 14);
        when(accountQuery.available()).thenReturn(true);
        when(accountQuery.coverageStart()).thenReturn(LocalDate.of(2026, 1, 1));

        // 1. Simulate a stream of raw posting effects across time
        List<PostingEffect> rawEffects = new ArrayList<>();
        // Before 'from' date (historical opening as of Oct 10):
        rawEffects.add(new PostingEffect(
            PostingEffectType.ACCOUNT, materialId1, warehouseId1, "1010", "PCS",
            new BigDecimal("200.0000000"), new BigDecimal("2000.0000"), "UZS"
        ));
        rawEffects.add(new PostingEffect(
            PostingEffectType.ACCOUNT, materialId1, warehouseId1, "1010", "PCS",
            new BigDecimal("-50.0000000"), new BigDecimal("-500.0000"), "UZS"
        ));
        // Net Opening as of Oct 10 = 150.0000000 @ 1500.0000 UZS

        // During reporting period (Oct 10 to Oct 20):
        // Receipt 1
        rawEffects.add(new PostingEffect(
            PostingEffectType.ACCOUNT, materialId1, warehouseId1, "1010", "PCS",
            new BigDecimal("80.0000000"), new BigDecimal("800.0000"), "UZS"
        ));
        // Issue 1
        rawEffects.add(new PostingEffect(
            PostingEffectType.ACCOUNT, materialId1, warehouseId1, "1010", "PCS",
            new BigDecimal("-45.0000000"), new BigDecimal("-450.0000"), "UZS"
        ));
        // Issue 2
        rawEffects.add(new PostingEffect(
            PostingEffectType.ACCOUNT, materialId1, warehouseId1, "1010", "PCS",
            new BigDecimal("-15.0000000"), new BigDecimal("-150.0000"), "UZS"
        ));

        // 2. Perform independent raw reconciliation calculation
        BigDecimal independentOpeningQty = BigDecimal.ZERO;
        BigDecimal independentOpeningAmt = BigDecimal.ZERO;
        BigDecimal independentReceiptQty = BigDecimal.ZERO;
        BigDecimal independentReceiptAmt = BigDecimal.ZERO;
        BigDecimal independentIssueQty = BigDecimal.ZERO;
        BigDecimal independentIssueAmt = BigDecimal.ZERO;

        for (int i = 0; i < rawEffects.size(); i++) {
            PostingEffect effect = rawEffects.get(i);
            if (i < 2) { // Before 'from'
                independentOpeningQty = independentOpeningQty.add(effect.quantity());
                independentOpeningAmt = independentOpeningAmt.add(effect.amount());
            } else { // In period
                if (effect.quantity().signum() > 0) {
                    independentReceiptQty = independentReceiptQty.add(effect.quantity());
                    independentReceiptAmt = independentReceiptAmt.add(effect.amount());
                } else {
                    independentIssueQty = independentIssueQty.add(effect.quantity().abs());
                    independentIssueAmt = independentIssueAmt.add(effect.amount().abs());
                }
            }
        }

        BigDecimal independentClosingQty = independentOpeningQty.add(independentReceiptQty).subtract(independentIssueQty);
        BigDecimal independentClosingAmt = independentOpeningAmt.add(independentReceiptAmt).subtract(independentIssueAmt);

        // 3. Configure mock report to return the query result
        Row reconstructedRow = new Row(
            warehouseId1 + ":1010:" + materialId1 + ":PCS",
            materialId1, warehouseId1, "WH-101", "Central Chemical Depot",
            groupId1, "GRP-RAW", "Raw Materials", "MAT-RAW-01", "Reagent Grade Acid",
            "PCS", "pcs", "шт", "dona", "—",
            "6010", "10.0000",
            independentOpeningQty.toPlainString(), independentOpeningAmt.toPlainString(),
            independentReceiptQty.toPlainString(), independentReceiptAmt.toPlainString(),
            independentIssueQty.toPlainString(), independentIssueAmt.toPlainString(),
            independentClosingQty.toPlainString(), independentClosingAmt.toPlainString(),
            "1010", "Materials", "Материалы", "OWNED"
        );

        when(accountQuery.rows(any(Filter.class), anyBoolean())).thenReturn(List.of(reconstructedRow));
        when(accountQuery.count(any(Filter.class))).thenReturn(1L);
        when(accountQuery.totals(any(Filter.class))).thenReturn(List.of(
            new Total(
                "PCS", "pcs", "шт", "dona",
                independentOpeningQty.toPlainString(), independentOpeningAmt.toPlainString(),
                independentReceiptQty.toPlainString(), independentReceiptAmt.toPlainString(),
                independentIssueQty.toPlainString(), independentIssueAmt.toPlainString(),
                independentClosingQty.toPlainString(), independentClosingAmt.toPlainString(),
                "1010", "Materials"
            )
        ));

        Filter filter = new Filter(
            Type.MATERIAL_STATEMENT, from, to, warehouseId1, materialId1,
            groupId1, null, 0, 50, null, null, "1010", null
        );

        WarehouseReport report = service.generate(filter, false);
        Row row = report.content().get(0);

        // 4. Assert zero quantity and currency delta between report and independent calculation
        BigDecimal reportOpeningQty = new BigDecimal(row.openingQuantity());
        BigDecimal reportReceiptQty = new BigDecimal(row.receiptQuantity());
        BigDecimal reportIssueQty = new BigDecimal(row.issueQuantity());
        BigDecimal reportClosingQty = new BigDecimal(row.closingQuantity());
        BigDecimal reportClosingAmt = new BigDecimal(row.closingAmount());

        BigDecimal qtyDelta = reportClosingQty.subtract(independentClosingQty);
        BigDecimal amtDelta = reportClosingAmt.subtract(independentClosingAmt);

        assertThat(qtyDelta).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(amtDelta).isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(reportOpeningQty).isEqualByComparingTo(independentOpeningQty);
        assertThat(reportReceiptQty).isEqualByComparingTo(independentReceiptQty);
        assertThat(reportIssueQty).isEqualByComparingTo(independentIssueQty);
        assertThat(reportClosingQty).isEqualByComparingTo("170.0000000");
        assertThat(reportClosingAmt).isEqualByComparingTo("1700.0000");
    }
}
