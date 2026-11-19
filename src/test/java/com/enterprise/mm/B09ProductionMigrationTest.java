package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class B09ProductionMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void baselineAndRecentMigrationsApplyToFreshPostgres() throws Exception {
        Flyway baseline = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .target("3")
            .load();

        assertThat(baseline.migrate().migrationsExecuted).isEqualTo(3);
        baseline.validate();
        assertThat(baseline.info().current().getVersion().toString()).isEqualTo("3");

        Flyway full = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .load();
        assertThat(full.migrate().migrationsExecuted).isEqualTo(32);
        full.validate();
        assertThat(full.info().current().getVersion().toString()).isEqualTo("35");

        try (Connection connection = POSTGRES.createConnection("")) {
            assertThat(hasColumn(connection, "materials", "incoming_inspection_required")).isTrue();
            assertThat(hasColumn(connection, "demand_plan_items", "stock_fulfillment_quantity")).isTrue();
        }
    }

    private static boolean hasColumn(Connection connection, String table, String column) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
            "select 1 from information_schema.columns where table_schema = 'public' and table_name = ? and column_name = ?"
        )) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }
}
