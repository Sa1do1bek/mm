package com.enterprise.mm.warehouse.reports;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@org.testcontainers.junit.jupiter.Testcontainers
class WarehouseReportStatisticsMigrationTest {
    @org.testcontainers.junit.jupiter.Container
    static final org.testcontainers.containers.PostgreSQLContainer<?> postgres =
        new org.testcontainers.containers.PostgreSQLContainer<>("postgres:17-alpine");

    @Test void fullFlywayBootstrapAppliesStatisticsBeforeHibernateCreatesAnyTables() {
        var flyway=Flyway.configure()
            .dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
            .locations("classpath:db/migration").load();
        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("13");
        flyway.validate();
    }
}
