package com.enterprise.mm.warehouse.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;

/** Deployment entry point: applies the additive projection without starting Spring or schedulers. */
public final class WarehouseProjectionMigrationRunner {
    private WarehouseProjectionMigrationRunner() {}

    public static void main(String[] args) {
        try {
            if (args.length != 1) throw new IllegalArgumentException("Expected private connection file");
            var connection = new ObjectMapper().readTree(Path.of(args[0]).toFile());
            String url = "jdbc:postgresql://" + connection.required("host").asText() + ":"
                    + connection.required("port").asInt() + "/" + connection.required("database").asText()
                    + "?sslmode=" + connection.required("sslmode").asText()
                    + "&currentSchema=public&ApplicationName=mm_warehouse_projection";
            migrate(url, connection.required("username").asText(), connection.required("password").asText(),
                    "classpath:db/migration");
        } catch (Exception failure) {
            System.err.println("Warehouse projection staging failed; keep the current backend running.");
            System.exit(1);
        }
    }

    static void migrate(String url, String username, String password, String location) {
        Flyway flyway = Flyway.configure().dataSource(url, username, password).locations(location)
                .target("9").cleanDisabled(true).validateMigrationNaming(true).validateOnMigrate(true)
                .connectRetries(0).lockRetryCount(0)
                .initSql("SET statement_timeout='600s'; SET lock_timeout='5s';").load();
        MigrationInfo current = flyway.info().current();
        if (current == null || !"8".equals(current.getVersion().getVersion())) {
            throw new IllegalStateException("Projection staging requires successful schema version 8");
        }
        flyway.migrate();
        if (!"9".equals(flyway.info().current().getVersion().getVersion())) {
            throw new IllegalStateException("Projection migration did not reach version 9");
        }
    }
}
