package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class AssetAcceptanceContractMigrationPostgresTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void backfillsExistingReceiptsAndRestoresAppendOnlyProtection() throws Exception {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("create table contracts (id uuid primary key)");
        jdbc.execute("create table purchase_orders (id uuid primary key, contract_id uuid)");
        jdbc.execute("create table asset_acceptances (id uuid primary key, purchase_order_id uuid)");
        jdbc.execute("create table goods_receipts (id uuid primary key, purchase_order_id uuid)");
        String baseline = new ClassPathResource("db/migration/V1__baseline_schema.sql")
            .getContentAsString(StandardCharsets.UTF_8);
        int start = baseline.indexOf("CREATE FUNCTION public.reject_goods_receipt_evidence_mutation()");
        jdbc.execute(baseline.substring(start, baseline.indexOf("$$;", start) + 3));
        jdbc.execute("""
            create trigger trg_goods_receipts_append_only before update or delete on goods_receipts
            for each row execute function reject_goods_receipt_evidence_mutation()
            """);
        String contract = "00000000-0000-0000-0000-000000000001";
        String order = "00000000-0000-0000-0000-000000000002";
        String receipt = "00000000-0000-0000-0000-000000000003";
        jdbc.update("insert into contracts values (?::uuid)", contract);
        jdbc.update("insert into purchase_orders values (?::uuid, ?::uuid)", order, contract);
        jdbc.update("insert into goods_receipts values (?::uuid, ?::uuid)", receipt, order);
        jdbc.update("insert into asset_acceptances values (?::uuid, ?::uuid)", receipt, order);
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute(new ClassPathResource("db/migration/V38__asset_acceptance_contract.sql")
                    .getContentAsString(StandardCharsets.UTF_8));
            }
            connection.commit();
        }
        assertThat(jdbc.queryForObject("select contract_id::text from goods_receipts", String.class))
            .isEqualTo(contract);
        assertThat(jdbc.queryForObject("select contract_id::text from asset_acceptances", String.class))
            .isEqualTo(contract);
        assertThatThrownBy(() -> jdbc.update("update goods_receipts set contract_id = null"))
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from goods_receipts"))
            .hasMessageContaining("append-only");
    }
}
