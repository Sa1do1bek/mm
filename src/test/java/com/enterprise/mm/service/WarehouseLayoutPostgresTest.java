package com.enterprise.mm.service;

import static org.junit.jupiter.api.Assertions.*;

import com.enterprise.mm.entity.WarehouseLayout;
import com.enterprise.mm.payload.WarehouseLayoutDtos.*;
import jakarta.persistence.OptimisticLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Arrays;
import java.util.UUID;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class WarehouseLayoutPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test void upgradesAnExistingV61DatabaseWithoutChangingItsHistory() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("create database layout_upgrade");
        }
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/layout_upgrade");
        var prior = org.flywaydb.core.Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
            .target("61").load();
        prior.migrate();
        assertEquals("61", prior.info().current().getVersion().getVersion());
        Integer checksum = prior.info().current().getChecksum();
        var upgrade = org.flywaydb.core.Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword()).load();
        assertEquals(1, upgrade.migrate().migrationsExecuted);
        assertEquals("62", upgrade.info().current().getVersion().getVersion());
        assertEquals(checksum, Arrays.stream(upgrade.info().applied())
            .filter(info -> "61".equals(info.getVersion().getVersion())).findFirst().orElseThrow().getChecksum());
        assertTrue(upgrade.validateWithResult().validationSuccessful);
        try (var connection = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement(); var rows = statement.executeQuery("select count(*) from warehouse_layouts")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }

    @Test void migrationJsonRoundTripAndOptimisticVersioning() throws Exception {
        UUID warehouseId = UUID.randomUUID(), companyId = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("create table companies (id uuid primary key); create table warehouses (id uuid primary key)");
            statement.execute("insert into companies values ('" + companyId + "'); insert into warehouses values ('" + warehouseId + "')");
            statement.execute(Files.readString(Path.of("src/main/resources/db/migration/V62__warehouse_layouts.sql")));
        }
        try (var factory = new Configuration().addAnnotatedClass(WarehouseLayout.class)
            .setProperty("hibernate.connection.url", postgres.getJdbcUrl())
            .setProperty("hibernate.connection.username", postgres.getUsername())
            .setProperty("hibernate.connection.password", postgres.getPassword())
            .setProperty("hibernate.hbm2ddl.auto", "validate").buildSessionFactory()) {
            var document = new Layout(40, 24, 8, List.of(new Item("rack-1", Kind.RACK, "Стеллаж A", 2, 3, 5, 2, 4, 90, null, null)));
            try (var session = factory.openSession()) {
                var tx = session.beginTransaction();
                var record = new WarehouseLayout(); record.setWarehouseId(warehouseId); record.setCompanyId(companyId);
                record.setTenantId("default"); record.setLayout(document); record.setUpdatedAt(LocalDateTime.now());
                session.persist(record); tx.commit(); assertEquals(0L, record.getVersion());
            }
            try (var first = factory.openSession(); var second = factory.openSession()) {
                var one = first.find(WarehouseLayout.class, warehouseId);
                var two = second.find(WarehouseLayout.class, warehouseId);
                assertEquals(document, one.getLayout());
                var tx = first.beginTransaction();
                one.setLayout(new Layout(50, 24, 8, document.items())); tx.commit(); assertEquals(1L, one.getVersion());
                var stale = second.beginTransaction();
                two.setLayout(new Layout(60, 24, 8, document.items()));
                assertThrows(OptimisticLockException.class, stale::commit);
            }
        }
    }
}
