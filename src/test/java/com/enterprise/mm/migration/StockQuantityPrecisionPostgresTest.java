package com.enterprise.mm.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Runs the real upgrade on a disposable database, independently of application fixtures. */
@Testcontainers(disabledWithoutDocker = false)
class StockQuantityPrecisionPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("mm_quantity_precision").withUsername("mm").withPassword("mm");
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration").target("6").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @Test
    void everyDeclaredQuantityColumnHasSevenPlacesAndPreservesIntegerCapacity() throws Exception {
        String sql = new ClassPathResource("db/migration/V6__stock_quantity_precision.sql")
            .getContentAsString(StandardCharsets.UTF_8);
        var tables = Pattern.compile("ALTER TABLE (\\w+)\\s+((?:\\s*ALTER COLUMN[^;]+)+);").matcher(sql);
        int checked = 0;
        while (tables.find()) {
            var columns = Pattern.compile("ALTER COLUMN (\\w+) TYPE numeric\\(22,7\\)").matcher(tables.group(2));
            while (columns.find()) {
                var definition = jdbc.queryForMap("select numeric_precision, numeric_scale from information_schema.columns "
                    + "where table_schema='public' and table_name=? and column_name=?", tables.group(1), columns.group(1));
                assertThat(definition.get("numeric_precision")).isEqualTo(22);
                assertThat(definition.get("numeric_scale")).isEqualTo(7);
                checked++;
            }
        }
        assertThat(checked).isEqualTo(164);
        assertThat(jdbc.queryForObject("select count(*) from warehouse_inventory_reconciliation_preflight", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select numeric_scale from information_schema.columns where table_schema='public' "
            + "and table_name='stock_balances' and column_name='total_value'", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select numeric_scale from information_schema.columns where table_schema='public' "
            + "and table_name='budget_transactions' and column_name='reserved_delta'", Integer.class)).isEqualTo(4);
    }

    @Test
    void physicalQuantityAndMoneyRoundTripWithoutLosingSourceDigits() {
        // A scratch table copies the real migrated column types, without unrelated business foreign keys.
        jdbc.execute("create table precision_probe as select quantity, total_value from stock_balances with no data");
        BigDecimal quantity = new BigDecimal("999999999999999.1234567");
        BigDecimal value = new BigDecimal("1315741275878.27");
        jdbc.update("insert into precision_probe(quantity,total_value) values (?,?)", quantity, value);
        assertThat(jdbc.queryForObject("select quantity from precision_probe", BigDecimal.class)).isEqualByComparingTo(quantity);
        assertThat(jdbc.queryForObject("select total_value from precision_probe", BigDecimal.class)).isEqualByComparingTo(value);
        assertThatThrownBy(() -> jdbc.update("insert into precision_probe(quantity,total_value) values (?,0)",
            new BigDecimal("1000000000000000"))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void configuredUnitScaleIsNullableAndConstrainedToZeroThroughSeven() {
        var id = jdbc.queryForObject("select id from unit_of_measures order by id limit 1", java.util.UUID.class);
        assertThat(id).isNotNull();
        assertThat(jdbc.queryForObject("select stock_quantity_scale from unit_of_measures where id=?", Integer.class, id)).isNull();
        jdbc.update("update unit_of_measures set stock_quantity_scale=7 where id=?", id);
        assertThat(jdbc.queryForObject("select stock_quantity_scale from unit_of_measures where id=?", Integer.class, id)).isEqualTo(7);
        for (int invalid : new int[]{-1,8}) {
            assertThatThrownBy(() -> jdbc.update("update unit_of_measures set stock_quantity_scale=? where id=?", invalid,id))
                .isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
