package com.enterprise.mm.warehouse.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.ConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;

/** Exercises the additive V13-to-V14 upgrade and its database invariants. */
class WarehousePostingMigrationPostgresTest {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate jdbc;
    private static DriverManagerDataSource dataSource;

    private final UUID company = UUID.fromString("26405241-8c87-4dc6-bc0a-b9f88af9f797");

    @BeforeAll
    static void migrate() {
        String externalUrl = System.getenv("WAREHOUSE_TEST_POSTGRES_JDBC_URL");
        if (externalUrl == null || externalUrl.isBlank()) {
            Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker or an isolated WAREHOUSE_TEST_POSTGRES_JDBC_URL is required");
            POSTGRES.start();
            dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        } else {
            dataSource = new DriverManagerDataSource(externalUrl,
                System.getenv().getOrDefault("WAREHOUSE_TEST_POSTGRES_USERNAME", "postgres"),
                System.getenv().getOrDefault("WAREHOUSE_TEST_POSTGRES_PASSWORD", ""));
        }
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("13").load().migrate();
        var upgrade = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("14").load();
        upgrade.migrate();
        assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("14");
        upgrade.validate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void stopContainer() {
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @BeforeEach
    void clearPostingEvidence() {
        jdbc.execute("delete from warehouse_posting_effects");
        jdbc.execute("delete from warehouse_posting_batches");
        jdbc.execute("delete from warehouse_posting_rules");
    }

    @AfterEach
    void clearScope() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void scopedCommandIdentityAllowsOtherCompaniesButRejectsDuplicateInOneCompany() {
        UUID rule = insertRule(company);
        insertBatch(company, rule, "post-1");
        assertThatThrownBy(() -> insertBatch(company, rule, "post-1"))
            .isInstanceOf(DataIntegrityViolationException.class);

        UUID otherCompany = UUID.randomUUID();
        insertBatch(otherCompany, insertRule(otherCompany), "post-1");
        assertThat(jdbc.queryForObject("select count(*) from warehouse_posting_batches", Integer.class)).isEqualTo(2);
    }

    @Test
    void nullableLocationBinAndBatchRemainOneStockPositionPerScope() {
        String tenant = "tenant-null-position-test";
        UUID material = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        try {
            insertStockBalance(tenant, company, material, warehouse);
            assertThatThrownBy(() -> insertStockBalance(tenant, company, material, warehouse))
                .isInstanceOf(DataIntegrityViolationException.class);

            insertStockBalance(tenant, UUID.randomUUID(), material, warehouse);
            assertThat(jdbc.queryForObject("select count(*) from stock_balances where tenant_id=?",
                Integer.class, tenant)).isEqualTo(2);
        } finally {
            jdbc.update("delete from stock_balances where tenant_id=?", tenant);
        }
    }

    @Test
    void valueOnlyCannotGainPhysicalQuantityAndStockNeedsWarehouseAndUnit() {
        UUID batch = insertBatch(company, insertRule(company), "post-2");
        insertEffect(batch, 1, "VALUE_ONLY", null, null, BigDecimal.ZERO, new BigDecimal("125.2300"));
        assertThatThrownBy(() -> insertEffect(batch, 2, "VALUE_ONLY", null, null,
            BigDecimal.ONE, new BigDecimal("125.2300"))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertEffect(batch, 3, "STOCK", null, "PCS",
            BigDecimal.ONE, BigDecimal.ZERO)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertEffect(batch, 4, "STOCK", UUID.randomUUID(), null,
            BigDecimal.ONE, BigDecimal.ZERO)).isInstanceOf(DataIntegrityViolationException.class);
        insertEffect(batch, 5, "STOCK", UUID.randomUUID(), "PCS", new BigDecimal("0.0000001"), BigDecimal.ZERO);
        assertThat(jdbc.queryForObject("select count(*) from warehouse_posting_effects", Integer.class)).isEqualTo(2);
    }

    @Test
    void failedTransactionLeavesNoPartialBatchOrEffect() {
        UUID rule = insertRule(company);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertThatThrownBy(() -> transaction.execute(status -> {
            UUID batch = insertBatch(company, rule, "rolled-back");
            insertEffect(batch, 1, "STOCK", UUID.randomUUID(), "PCS", BigDecimal.ONE, BigDecimal.ZERO);
            throw new IllegalStateException("account effect failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from warehouse_posting_batches", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from warehouse_posting_effects", Integer.class)).isZero();
    }

    @Test
    void rulePreviewReadsApprovedRuleWithoutWritingPostingEvidence() {
        UUID ruleId = insertRule(company);
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(company);
        var service = new PostingJournalService(new JdbcPostingEvidenceStore(jdbc,
            new ObjectMapper().findAndRegisterModules()));

        var preview = service.preview(new PostingRuleQuery("WAREHOUSE", "ISSUE", "OWNED",
            java.time.LocalDate.of(2026, 10, 14)));

        assertThat(preview.id()).isEqualTo(ruleId);
        assertThat(jdbc.queryForObject("select count(*) from warehouse_posting_batches", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from warehouse_posting_effects", Integer.class)).isZero();
    }

    @Test
    void committedJournalReplayReturnsReceiptWithoutRepeatingStockAction() throws Exception {
        insertRule(company);
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(company);
        var service = new PostingJournalService(new JdbcPostingEvidenceStore(jdbc,
            new ObjectMapper().findAndRegisterModules()));
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var command = new PostingCommand("WAREHOUSE", "ISSUE", "OWNED",
            UUID.fromString("5539dc9f-6ce6-4f8c-9815-0ccf96a80bbd"), "journal-1",
            java.time.LocalDate.of(2026, 10, 14), 7L,
            new ObjectMapper().readTree("{\"quantity\":3}"));
        AtomicInteger actions = new AtomicInteger();
        var first = transaction.execute(status -> service.execute(command, rule -> {
            actions.incrementAndGet();
            return List.of(new PostingEffect(PostingEffectType.STOCK, UUID.randomUUID(), UUID.randomUUID(),
                "1010", "PCS", new BigDecimal("-3"), BigDecimal.ZERO, null));
        }));
        var replay = transaction.execute(status -> service.execute(command, rule -> {
            throw new ConflictException("stale document version");
        }));
        var changed = new PostingCommand(command.book(), command.operation(), command.contextKey(),
            command.resourceId(), command.idempotencyKey(), command.postingDate(), command.expectedVersion(),
            new ObjectMapper().readTree("{\"quantity\":4}"));

        assertThatThrownBy(() -> transaction.execute(status -> service.execute(changed, rule -> {
            actions.incrementAndGet();
            return List.of(new PostingEffect(PostingEffectType.STOCK, UUID.randomUUID(), UUID.randomUUID(),
                "1010", "PCS", new BigDecimal("-4"), BigDecimal.ZERO, null));
        }))).isInstanceOf(ConflictException.class).hasMessageContaining("different");

        assertThat(replay).isEqualTo(first);
        assertThat(actions).hasValue(1);
        assertThat(jdbc.queryForObject("select count(*) from warehouse_posting_batches", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from warehouse_posting_effects", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyProducesOneCommittedReceipt() throws Exception {
        insertRule(company);
        var service = new PostingJournalService(new JdbcPostingEvidenceStore(jdbc,
            new ObjectMapper().findAndRegisterModules()));
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var command = new PostingCommand("WAREHOUSE", "ISSUE", "OWNED", UUID.randomUUID(), "concurrent-1",
            java.time.LocalDate.of(2026, 10, 14), 7L,
            new ObjectMapper().readTree("{\"quantity\":3}"));
        var start = new CountDownLatch(1);
        AtomicInteger actions = new AtomicInteger();
        var workers = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<PostingReceipt> call = () -> {
                TenantContext.setTenant("tenant-a");
                CompanyContext.setCompany(company);
                try {
                    start.await();
                    return transaction.execute(status -> service.execute(command, rule -> {
                        actions.incrementAndGet();
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                        return List.of(new PostingEffect(PostingEffectType.STOCK, UUID.randomUUID(), UUID.randomUUID(),
                            "1010", "PCS", new BigDecimal("-3"), BigDecimal.ZERO, null));
                    }));
                } finally {
                    TenantContext.clear();
                    CompanyContext.clear();
                }
            };
            var first = workers.submit(call);
            var second = workers.submit(call);
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
            assertThat(actions).hasValue(1);
            assertThat(jdbc.queryForObject("select count(*) from warehouse_posting_batches", Integer.class)).isEqualTo(1);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void failedPostingActionRollsBackItsReservation() throws Exception {
        insertRule(company);
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(company);
        var service = new PostingJournalService(new JdbcPostingEvidenceStore(jdbc,
            new ObjectMapper().findAndRegisterModules()));
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var command = new PostingCommand("WAREHOUSE", "ISSUE", "OWNED", UUID.randomUUID(), "failed-post",
            java.time.LocalDate.of(2026, 10, 14), 7L,
            new ObjectMapper().readTree("{\"quantity\":3}"));

        assertThatThrownBy(() -> transaction.execute(status -> service.execute(command, rule -> {
            throw new IllegalStateException("cost allocation failed");
        }))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from warehouse_posting_batches", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from warehouse_posting_effects", Integer.class)).isZero();
    }

    @Test
    void accountIsolationCalculatesCorrectAvailableQuantityFromCommittedEffects() {
        var store = new JdbcPostingEvidenceStore(jdbc, new ObjectMapper().findAndRegisterModules());
        UUID material = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        UUID ruleId = insertRule(company);

        // Before any batches exist for this material/warehouse, returns null
        assertThat(store.getAccountAvailableQuantity("tenant-a", company, material, warehouse, "1010"))
            .isNull();

        // 1. Commit receipt for 10 units on Account 1010
        UUID batch1 = insertBatch(company, ruleId, "key-rcpt-1010");
        insertEffectWithMaterial(batch1, 1, "ACCOUNT", material, warehouse, "1010", new BigDecimal("10.0000000"), new BigDecimal("10000.0000"));
        jdbc.update("update warehouse_posting_batches set status='COMMITTED', receipt_json='{}'::jsonb, committed_at=current_timestamp where id=?", batch1);

        // 2. Commit receipt for 5 units on Account 1020
        UUID batch2 = insertBatch(company, ruleId, "key-rcpt-1020");
        insertEffectWithMaterial(batch2, 1, "ACCOUNT", material, warehouse, "1020", new BigDecimal("5.0000000"), new BigDecimal("10000.0000"));
        jdbc.update("update warehouse_posting_batches set status='COMMITTED', receipt_json='{}'::jsonb, committed_at=current_timestamp where id=?", batch2);

        // Verify isolation
        assertThat(store.getAccountAvailableQuantity("tenant-a", company, material, warehouse, "1010"))
            .isEqualByComparingTo("10.0000000");
        assertThat(store.getAccountAndOwnerAvailableQuantityForUnit("tenant-a", company, material,
            warehouse, "1010", null, "OWNED", "PCS", "UZS")).isEqualByComparingTo("10.0000000");
        assertThat(store.getAccountAndOwnerAvailableQuantityForUnit("tenant-a", company, material,
            warehouse, "1010", null, "OWNED", "KG", "UZS")).isEqualByComparingTo("0");
        assertThat(store.getAccountAndOwnerAvailableQuantityForUnit("tenant-a", company, material,
            warehouse, "1010", null, "OWNED", "PCS", "USD")).isEqualByComparingTo("0");
        assertThat(store.getAccountAvailableQuantity("tenant-a", company, material, warehouse, "1020"))
            .isEqualByComparingTo("5.0000000");
        assertThat(store.getAccountAvailableQuantity("tenant-a", company, material, warehouse, "1030"))
            .isEqualByComparingTo("0.0000000");

        // 3. Commit issue of 4 units from Account 1010
        UUID batch3 = insertBatch(company, ruleId, "key-iss-1010");
        insertEffectWithMaterial(batch3, 1, "ACCOUNT", material, warehouse, "1010", new BigDecimal("-4.0000000"), new BigDecimal("-4000.0000"));
        jdbc.update("update warehouse_posting_batches set status='COMMITTED', receipt_json='{}'::jsonb, committed_at=current_timestamp where id=?", batch3);

        // Verify Account 1010 has 6 units left and Account 1020 remains exactly 5 units
        assertThat(store.getAccountAvailableQuantity("tenant-a", company, material, warehouse, "1010"))
            .isEqualByComparingTo("6.0000000");
        assertThat(store.getAccountAvailableQuantity("tenant-a", company, material, warehouse, "1020"))
            .isEqualByComparingTo("5.0000000");
    }

    private void insertEffectWithMaterial(UUID batch, int sequence, String type, UUID materialId,
                                          UUID warehouse, String accountCode, BigDecimal quantity, BigDecimal amount) {
        jdbc.update("insert into warehouse_posting_effects "
                + "(batch_id,effect_sequence,effect_type,material_id,warehouse_id,account_code,base_unit_code,"
                + "quantity,amount,currency_code) values (?,?,?,?,?,?,'PCS',?,?,'UZS')",
            batch, sequence, type, materialId, warehouse, accountCode, quantity, amount);
    }

    private UUID insertRule(UUID companyId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into warehouse_posting_rules "
                + "(id,tenant_id,company_id,book,operation,context_key,rule_version,effective_from,approved,"
                + "account_code,currency_code,rule_snapshot,rule_hash) "
                + "values (?,'tenant-a',?,'WAREHOUSE','ISSUE','OWNED',1,date '2026-10-01',true,'1010','UZS',"
                + "?::jsonb,?)",
            id, companyId, "{\"accountCode\":\"1010\"}", "a".repeat(64));
        return id;
    }

    private void insertStockBalance(String tenant, UUID companyId, UUID materialId, UUID warehouseId) {
        jdbc.update("insert into stock_balances "
                + "(id,tenant_id,company_id,created_at,name,material_id,warehouse_id,"
                + "quantity,available_quantity,reserved_quantity,total_value) "
                + "values (?,?,?,current_timestamp,'null position',?,?,0,0,0,0)",
            UUID.randomUUID(), tenant, companyId, materialId, warehouseId);
    }

    private UUID insertBatch(UUID companyId, UUID ruleId, String key) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into warehouse_posting_batches "
                + "(id,tenant_id,company_id,book,operation,context_key,resource_id,idempotency_key,request_hash,"
                + "posting_date,expected_version,rule_id,rule_version,rule_snapshot,status) "
                + "values (?,'tenant-a',?,'WAREHOUSE','ISSUE','OWNED',?,?,?,date '2026-10-14',7,?,1,?::jsonb,'PENDING')",
            id, companyId, UUID.fromString("5539dc9f-6ce6-4f8c-9815-0ccf96a80bbd"), key,
            "b".repeat(64), ruleId, "{\"accountCode\":\"1010\"}");
        return id;
    }

    private void insertEffect(UUID batch, int sequence, String type, UUID warehouse, String unit,
                              BigDecimal quantity, BigDecimal amount) {
        jdbc.update("insert into warehouse_posting_effects "
                + "(batch_id,effect_sequence,effect_type,material_id,warehouse_id,account_code,base_unit_code,"
                + "quantity,amount,currency_code) values (?,?,?,?,?,'1010',?,?,?,'UZS')",
            batch, sequence, type, UUID.randomUUID(), warehouse, unit, quantity, amount);
    }
}
