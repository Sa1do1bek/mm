package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class InventoryCountUpgradeTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void baselineDistinguishesUntouchedCountFromObservedZero() {
        Flyway.configure()
            .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
            .load()
            .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));

        assertThat(nullable(jdbc, "inventory_items", "counted_quantity")).isEqualTo("YES");
        assertThat(nullable(jdbc, "inventory_documents", "approved_count_revision")).isEqualTo("YES");
        assertThat(jdbc.queryForObject(
            "select count(*) from information_schema.tables where table_schema = 'public' and table_name = 'inventory_line_history'",
            Integer.class)).isEqualTo(1);
    }

    private String nullable(JdbcTemplate jdbc, String table, String column) {
        return jdbc.queryForObject(
            "select is_nullable from information_schema.columns where table_schema = 'public' and table_name = ? and column_name = ?",
            String.class, table, column);
    }
}
