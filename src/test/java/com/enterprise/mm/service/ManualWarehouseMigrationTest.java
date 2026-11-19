package com.enterprise.mm.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class ManualWarehouseMigrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test void upgradesExistingProcurementSchemaAndRequiresTrackedPhysicalUnitOrigins() {
        String url = postgres.getJdbcUrl(), user = postgres.getUsername(), password = postgres.getPassword();
        Flyway.configure().dataSource(url, user, password).target("15").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        assertThat(nullable(jdbc, "asset_acceptances", "purchase_order_id")).isEqualTo("NO");
        var upgrade = Flyway.configure().dataSource(url, user, password).target("16").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        upgrade.validate();
        assertThat(nullable(jdbc, "asset_acceptances", "purchase_order_id")).isEqualTo("YES");
        assertThat(nullable(jdbc, "asset_acceptance_lines", "purchase_order_item_id")).isEqualTo("YES");
        assertThat(nullable(jdbc, "asset_acceptance_units", "purchase_order_item_id")).isEqualTo("YES");
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conrelid='material_units'::regclass and conname='ck_material_unit_receipt_source' and convalidated", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.execute("insert into material_units(id,tenant_id,company_id,created_at,version,unit_status,material_id,manufacturer_serial_number,normalized_serial_number,warehouse_id,received_at,name) values(gen_random_uuid(),'manual-test',gen_random_uuid(),now(),0,'IN_STOCK',gen_random_uuid(),'MANUAL-TEST','MANUAL-TEST',gen_random_uuid(),now(),'Manual test')"))
            .hasMessageContaining("ck_material_unit_receipt_source");
    }
    private String nullable(JdbcTemplate jdbc, String table, String column) {
        return jdbc.queryForObject("select is_nullable from information_schema.columns where table_name=? and column_name=?", String.class, table, column);
    }
}
