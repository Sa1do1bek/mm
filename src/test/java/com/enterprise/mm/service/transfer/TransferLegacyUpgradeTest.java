package com.enterprise.mm.service.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The current baseline includes the former transfer-history upgrade. */
@Testcontainers(disabledWithoutDocker = true)
class TransferLegacyUpgradeTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void baselinePreservesTransferReconciliationAndImmutableShipmentSchema() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .load()
            .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

        assertThat(jdbc.queryForObject(
            "select column_default from information_schema.columns where table_schema = 'public' "
                + "and table_name = 'transfer_documents' and column_name = 'reconciliation_status'",
            String.class)).contains("NOT_REQUIRED");
        assertThat(jdbc.queryForObject(
            "select count(*) from pg_indexes where schemaname = 'public' and indexname = 'ix_transfer_open_reconciliation'",
            Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select count(*) from information_schema.tables where table_schema = 'public' and table_name = 'transfer_shipments'",
            Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select count(*) from pg_trigger where tgname = 'transfer_shipments_immutable' and not tgisinternal",
            Integer.class)).isEqualTo(1);
    }
}
