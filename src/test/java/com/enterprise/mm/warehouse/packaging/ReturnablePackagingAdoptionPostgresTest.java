package com.enterprise.mm.warehouse.packaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class ReturnablePackagingAdoptionPostgresTest {
    private static final UUID COMPANY = UUID.fromString("753e618e-8d47-4efd-a41e-df8311b83d2f");
    private static final UUID IMPORT = UUID.fromString("42c00ffd-ddac-5bfc-86bf-93924ac6c036");

    @Test void realSchemaAdoptsAllReviewedBalancesWithoutChangingStockOrInvalidLegacyCodes() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .target("59").load().migrate();
            JsonNode scope = new ObjectMapper().readTree(getClass().getResourceAsStream("/packaging-legacy-adoption-scope.json"));
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                insert(db, "insert into companies(id,company_id,tenant_id,code,name,created_at) values(?,?,'default','NAVOIYAZOT-1C-20261008','Opening fixture',now())", COMPANY, COMPANY);
                BigDecimal quantity = BigDecimal.ZERO;
                for (var row : scope) quantity = quantity.add(new BigDecimal(row.get("quantity").asText()));
                insert(db, """
                    insert into exact_opening_imports(id,tenant_id,company_id,source_manifest_sha256,prepared_manifest_sha256,
                        source_organization_id,source_evidence,posting_date,expected_rows,expected_quantity,expected_value,
                        accepted_rows,accepted_quantity,accepted_value,status,created_at,completed_at)
                    values(?,'default',?,?,?,?,'test','2026-10-08',159,?,12345.67,159,?,12345.67,'COMPLETED',now(),now())
                    """, IMPORT, COMPANY, "a".repeat(64), "b".repeat(64), "c".repeat(32), quantity, quantity);
                Set<UUID> materials = new HashSet<>(), warehouses = new HashSet<>();
                for (var row : scope) {
                    UUID material = id(row, "materialId"), warehouse = id(row, "warehouseId"), batch = id(row, "batchId");
                    if (materials.add(material)) insert(db, """
                        insert into materials(id,tenant_id,company_id,created_at,name,material_type,material_subtype,
                            tracking_mode,batch_tracking_policy,serial_tracking_policy,description,ikpu_code,status)
                        values(?,'default',?,now(),'Drum','TMB','MATERIAL','QUANTITY','OPTIONAL','NONE',?,'UNRESOLVED-LEGACY','ACTIVE')
                        """, material, COMPANY, "Catalog54:" + row.get("sourceId").asText());
                    if (warehouses.add(warehouse)) insert(db, "insert into warehouses(id,tenant_id,company_id,created_at,name) values(?,'default',?,now(),'Warehouse')", warehouse, COMPANY);
                    BigDecimal q = new BigDecimal(row.get("quantity").asText());
                    insert(db, """
                        insert into stock_batches(id,tenant_id,company_id,created_at,name,material_id,warehouse_id,quantity,unit_cost)
                        values(?,'default',?,now(),'Opening batch',?,?,?,0.3333)
                        """, batch, COMPANY, material, warehouse, q);
                    UUID movement = UUID.randomUUID();
                    insert(db, """
                        insert into stock_movements(id,tenant_id,company_id,created_at,name,material_id,warehouse_id,stock_batch_id,
                            movement_type,status,quantity,reference_type,reference_id)
                        values(?,'default',?,now(),'Exact opening',?,?,?,'RECEIPT','POSTED',?,'OPENING_STOCK_EXACT',?)
                        """, movement, COMPANY, material, warehouse, batch, q, IMPORT);
                    insert(db, """
                        insert into stock_balances(id,tenant_id,company_id,created_at,name,material_id,warehouse_id,stock_batch_id,
                            quantity,available_quantity,reserved_quantity,quarantine_quantity,total_value,stock_category)
                        values(?,'default',?,now(),'Opening balance',?,?,?,?,?,0,0,12345.6701,'OWNED')
                        """, id(row, "balanceId"), COMPANY, material, warehouse, batch, q, q);
                    insert(db, """
                        insert into exact_opening_rows(id,import_id,source_row_id,account_source_id,account_code,
                            material_source_id,warehouse_source_id,movement_id,quantity,total_value)
                        values(?,?,?,?,?,?,?,?,?,12345.6701)
                        """, UUID.randomUUID(), IMPORT, "source-" + movement, "d".repeat(32), row.get("accountCode").asText(), row.get("sourceId").asText(),
                        warehouse.toString().replace("-", ""), movement, q);
                }
                Flyway flyway = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).load();
                flyway.migrate(); flyway.validate();
                try (var statement = db.createStatement(); var result = statement.executeQuery("""
                    select count(*),bool_and(l.opening_value=b.total_value and l.unknown_quantity=b.quantity
                        and l.received_quantity=b.quantity and l.supplier_id is null and l.content_material_id is null)
                    from returnable_packaging_lots l join stock_balances b on b.id=l.legacy_stock_balance_id
                    """)) {
                    result.next(); assertThat(result.getInt(1)).isEqualTo(159); assertThat(result.getBoolean(2)).isTrue();
                }
                try (var statement = db.createStatement(); var result = statement.executeQuery("""
                    select material_count,lot_count,stock_before=stock_after,material_before_md5=material_after_md5
                    from returnable_packaging_legacy_migrations
                    """)) {
                    result.next(); assertThat(result.getInt(1)).isEqualTo(112); assertThat(result.getInt(2)).isEqualTo(159);
                    assertThat(result.getBoolean(3)).isTrue(); assertThat(result.getBoolean(4)).isTrue();
                }
            }
        }
    }

    private static UUID id(JsonNode row, String key) { return UUID.fromString(row.get(key).asText()); }
    private static void insert(Connection db, String sql, Object... values) throws Exception {
        try (var statement = db.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }
}
