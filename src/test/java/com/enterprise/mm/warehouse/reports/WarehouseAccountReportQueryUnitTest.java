package com.enterprise.mm.warehouse.reports;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WarehouseAccountReportQueryUnitTest {

    private JdbcOperations jdbcOperations;
    private NamedParameterJdbcTemplate namedJdbc;
    private WarehouseReportQuery operational;
    private WarehouseAccountReportQuery query;

    private final UUID materialId = UUID.randomUUID();
    private final UUID warehouseId = UUID.randomUUID();
    private final LocalDate from = LocalDate.of(2026, 10, 1);
    private final LocalDate to = LocalDate.of(2026, 10, 18);

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("navoiy-azot");
        CompanyContext.setCompany(UUID.randomUUID());
        jdbcOperations = mock(JdbcOperations.class);
        namedJdbc = new NamedParameterJdbcTemplate(jdbcOperations);
        operational = new WarehouseReportQuery(namedJdbc);

        when(jdbcOperations.query(any(PreparedStatementCreator.class), any(RowMapper.class)))
            .thenAnswer(inv -> {
                Object psc = inv.getArgument(0);
                String sql = (psc instanceof org.springframework.jdbc.core.SqlProvider sp) ? sp.getSql() : psc.toString();
                if (sql.contains("from opening_balance o cross join")) {
                    var rs = mock(java.sql.ResultSet.class);
                    when(rs.getBigDecimal(any(String.class))).thenReturn(BigDecimal.ZERO);
                    when(rs.getLong("movement_count")).thenReturn(0L);
                    RowMapper<?> mapper = inv.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                } else if (sql.contains("count(*)")) {
                    return List.of(0L);
                } else if (sql.contains("rec_qty")) {
                    return Collections.singletonList(new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                } else if (sql.contains("from opening_balance")) {
                    return Collections.singletonList(new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                } else if (sql.contains("min(account_name)")) {
                    return List.of("Счёт 1010");
                }
                return Collections.emptyList();
            });

        query = new WarehouseAccountReportQuery(namedJdbc, operational);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    @DisplayName("materialCard query parses and executes successfully without 'materialunion' parameter error when warehouseId and accountCode are null")
    void materialCard_nullWarehouseAndAccount_sqlParametersParseCorrectly() {
        assertThatCode(() -> query.materialCard(materialId, null, from, to, null, 0, 5, "UZS"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("materialCard query parses successfully when warehouseId is provided but accountCode is null")
    void materialCard_withWarehouseOnly_sqlParametersParseCorrectly() {
        assertThatCode(() -> query.materialCard(materialId, warehouseId, from, to, null, 0, 5, "UZS"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("materialCard query parses successfully when accountCode is provided but warehouseId is null")
    void materialCard_withAccountOnly_sqlParametersParseCorrectly() {
        assertThatCode(() -> query.materialCard(materialId, null, from, to, "1010", 0, 5, "UZS"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("materialCard query parses successfully when both warehouseId and accountCode are provided")
    void materialCard_withBothWarehouseAndAccount_sqlParametersParseCorrectly() {
        assertThatCode(() -> query.materialCard(materialId, warehouseId, from, to, "1010", 0, 5, "UZS"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("MATERIAL_STATEMENT rows, count, and totals parse and bind parameters correctly without proven_accounts")
    void report_materialStatement_parsesAndBindsCorrectly() {
        var filter = new WarehouseReport.Filter(WarehouseReport.Type.MATERIAL_STATEMENT, from, to, null, null, null, null, 0, 5);
        assertThatCode(() -> {
            query.rows(filter, false);
            query.count(filter);
            query.totals(filter);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("STOCK_BALANCES rows, count, and totals parse and bind parameters correctly without proven_accounts")
    void report_stockBalances_parsesAndBindsCorrectly() {
        var filter = new WarehouseReport.Filter(WarehouseReport.Type.STOCK_BALANCES, from, to, null, null, null, null, 0, 5);
        assertThatCode(() -> {
            query.rows(filter, false);
            query.count(filter);
            query.totals(filter);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("RECEIPTS rows, count, and totals parse and bind parameters correctly without proven_accounts")
    void report_receipts_parsesAndBindsCorrectly() {
        var filter = new WarehouseReport.Filter(WarehouseReport.Type.RECEIPTS, from, to, null, null, null, null, 0, 5);
        assertThatCode(() -> {
            query.rows(filter, false);
            query.count(filter);
            query.totals(filter);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ISSUES rows, count, and totals parse and bind parameters correctly without proven_accounts")
    void report_issues_parsesAndBindsCorrectly() {
        var filter = new WarehouseReport.Filter(WarehouseReport.Type.ISSUES, from, to, null, null, null, null, 0, 5);
        assertThatCode(() -> {
            query.rows(filter, false);
            query.count(filter);
            query.totals(filter);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("warehouseTotals (overview query) parses and binds parameters correctly without proven_accounts")
    void report_overviewWarehouseTotals_parsesAndBindsCorrectly() {
        var filter = new WarehouseReport.Filter(WarehouseReport.Type.MATERIAL_STATEMENT, from, to, null, null, null, null, 0, 5);
        assertThatCode(() -> query.warehouseTotals(filter)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("STOCK_BALANCES with snapshotTimestamp parses and binds :snapshotTimestamp parameter correctly")
    void report_stockBalancesWithSnapshotTimestamp_parsesAndBindsCorrectly() {
        var snapshot = java.time.LocalDateTime.parse("2026-10-15T18:00:00");
        var filter = new WarehouseReport.Filter(WarehouseReport.Type.STOCK_BALANCES, from, to, null, null, null, null, 0, 5,
            null, null, null, null, null, snapshot);
        assertThatCode(() -> {
            query.rows(filter, false);
            query.count(filter);
            query.totals(filter);
        }).doesNotThrowAnyException();
    }
}

