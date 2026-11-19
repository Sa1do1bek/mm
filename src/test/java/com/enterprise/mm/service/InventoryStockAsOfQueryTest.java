package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.error.BusinessException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class InventoryStockAsOfQueryTest {
    private static final String TENANT = "as-of-test";
    private static final UUID COMPANY = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WAREHOUSE = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final UUID balance = UUID.randomUUID();
    private JdbcTemplate jdbc;
    private InventoryStockAsOfQuery query;

    @BeforeEach void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:asof-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table stock_balances (id uuid primary key, tenant_id varchar(80), company_id uuid, material_id uuid, warehouse_id uuid, is_delete boolean, quantity numeric(22,7), total_value numeric(19,4))");
        jdbc.execute("create table stock_movements (id uuid primary key, tenant_id varchar(80), company_id uuid, material_id uuid, warehouse_id uuid, target_warehouse_id uuid, movement_date date, posted_at timestamp)");
        jdbc.execute("create table stock_movement_allocations (id uuid primary key, tenant_id varchar(80), company_id uuid, movement_id uuid, material_id uuid, balance_id uuid, on_hand_delta numeric(22,7), total_value_delta numeric(19,4))");
        jdbc.execute("create table warehouse_posting_batches (id uuid primary key, tenant_id varchar(80), company_id uuid, status varchar(30))");
        jdbc.execute("create table warehouse_posting_effects (id uuid primary key, batch_id uuid, warehouse_id uuid, effect_type varchar(30))");
        query = new InventoryStockAsOfQuery(new NamedParameterJdbcTemplate(dataSource));
    }

    @Test void reconstructsCutoffWhileCurrentBalanceHasMoved() {
        insertBalance(balance, "7", "70");
        movement("2026-10-01", "10", "100");
        movement("2026-10-05", "-3", "-30");

        var result = query.historical(TENANT, COMPANY, List.of(WAREHOUSE), LocalDate.of(2026, 10, 1));

        assertThat(result.get(balance).quantity()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(result.get(balance).value()).isEqualByComparingTo(new BigDecimal("100"));
    }

    @Test void incompleteMovementEvidenceCannotBePresentedAsHistoricalStock() {
        insertBalance(balance, "7", "70");
        movement("2026-10-01", "10", "100");

        assertThatThrownBy(() -> query.historical(TENANT, COMPANY, List.of(WAREHOUSE), LocalDate.of(2026, 10, 1)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("incomplete");
    }

    @Test void laterBackdatedPostingMarksCutoffBasisStale() {
        insertBalance(balance, "7", "70");
        movement("2026-10-01", "7", "70");
        jdbc.update("update stock_movements set posted_at=?", java.time.LocalDateTime.of(2026, 10, 20, 12, 0));

        assertThat(query.hasLateBackdatedMovement(TENANT, COMPANY, List.of(WAREHOUSE),
            LocalDate.of(2026, 10, 1), java.time.LocalDateTime.of(2026, 10, 10, 0, 0))).isTrue();
        assertThat(query.hasLateBackdatedMovement(TENANT, COMPANY, List.of(WAREHOUSE),
            LocalDate.of(2026, 10, 1), java.time.LocalDateTime.of(2026, 11, 10, 0, 0))).isFalse();
    }

    private void insertBalance(UUID id, String quantity, String value) {
        jdbc.update("insert into stock_balances values (?,?,?,?,?,?,?,?)", id, TENANT, COMPANY,
            UUID.fromString("00000000-0000-0000-0000-000000000003"), WAREHOUSE, false,
            new BigDecimal(quantity), new BigDecimal(value));
    }

    private void movement(String date, String quantity, String value) {
        UUID id = UUID.randomUUID(), material = UUID.fromString("00000000-0000-0000-0000-000000000003");
        jdbc.update("insert into stock_movements values (?,?,?,?,?,?,?,?)", id, TENANT, COMPANY, material, WAREHOUSE, null,
            LocalDate.parse(date), java.time.LocalDateTime.parse(date + "T12:00:00"));
        jdbc.update("insert into stock_movement_allocations values (?,?,?,?,?,?,?,?)", UUID.randomUUID(), TENANT, COMPANY,
            id, material, balance, new BigDecimal(quantity), new BigDecimal(value));
    }
}
