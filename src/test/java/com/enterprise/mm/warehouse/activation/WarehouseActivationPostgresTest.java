package com.enterprise.mm.warehouse.activation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.service.FinishedGoodsOpeningReader;
import com.enterprise.mm.warehouse.posting.AccountAwareStockPostingService;
import com.enterprise.mm.warehouse.posting.PostingReceipt;
import com.enterprise.mm.warehouse.posting.PostingEffect;
import com.enterprise.mm.warehouse.posting.PostingEffectType;
import com.enterprise.mm.warehouse.posting.JdbcPostingEvidenceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

class WarehouseActivationPostgresTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate jdbc;
    private static DriverManagerDataSource dataSource;

    private final String tenant = "activation-test";
    private final UUID company = UUID.randomUUID();
    private final LocalDate snapshotDate = LocalDate.of(2026, 10, 14);
    private final LocalDate postingDate = LocalDate.of(2026, 10, 16);

    @BeforeAll
    static void migrate() {
        String externalUrl = System.getenv("WAREHOUSE_TEST_POSTGRES_JDBC_URL");
        if (externalUrl == null || externalUrl.isBlank()) {
            Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable());
            POSTGRES.start();
            dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        } else {
            dataSource = new DriverManagerDataSource(externalUrl,
                System.getenv().getOrDefault("WAREHOUSE_TEST_POSTGRES_USERNAME", "postgres"),
                System.getenv().getOrDefault("WAREHOUSE_TEST_POSTGRES_PASSWORD", ""));
        }
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
            .load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table if not exists activation_test_postings (plan_id uuid, command_key text)");
    }

    @AfterAll
    static void stopContainer() {
        if (POSTGRES.isRunning()) POSTGRES.stop();
    }

    @AfterEach
    void clearScope() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void previewUsesOnlyTheSelectedCompletedSnapshot() {
        TenantContext.setTenant(tenant);
        CompanyContext.setCompany(company);
        UUID material = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        UUID first = insertImport(snapshotDate.minusDays(1), "first");
        UUID second = insertImport(snapshotDate, "second");
        insertBalance(first, "first-row", snapshotDate.minusDays(1), material, warehouse, "5.0000000");
        insertBalance(second, "second-row", snapshotDate, material, warehouse, "7.0000000");
        completeImport(first);
        completeImport(second);

        var service = new ActivationPreviewService(new NamedParameterJdbcTemplate(dataSource));
        var selected = service.preview(new ActivationPreviewQuery("1010", warehouse, null,
            0, 50, second, snapshotDate));
        assertThat(selected.items()).extracting(ActivationPreviewCandidate::sourceKey)
            .containsExactly("second-row");
        assertThat(selected.items().get(0).deltaQuantity()).isEqualByComparingTo("7.0000000");

        var defaultLatest = service.preview(new ActivationPreviewQuery("1010", warehouse, null, 0, 50));
        assertThat(defaultLatest.items()).extracting(ActivationPreviewCandidate::sourceKey)
            .containsExactly("second-row");
        assertThatThrownBy(() -> jdbc.update(
            "update warehouse_account_entries set quantity=9 where import_id=?", second))
            .isInstanceOf(org.springframework.dao.DataAccessException.class)
            .hasMessageContaining("immutable");
    }

    @Test
    void defaultPreviewIncludesAllCompletedImportsFromLatestSnapshot() {
        TenantContext.setTenant(tenant);
        CompanyContext.setCompany(company);
        UUID warehouse = UUID.randomUUID();
        UUID first = insertImport(snapshotDate, "base-import");
        UUID second = insertImport(snapshotDate, "supplemental-import");
        insertBalance(first, "base-balance", snapshotDate, UUID.randomUUID(), warehouse, "5");
        insertBalance(second, "supplemental-balance", snapshotDate, UUID.randomUUID(), warehouse, "7");
        completeImport(first);
        completeImport(second);

        var service = new ActivationPreviewService(new NamedParameterJdbcTemplate(dataSource));
        var all = service.preview(new ActivationPreviewQuery("1010", warehouse, null, 0, 50));
        assertThat(all.items()).extracting(ActivationPreviewCandidate::sourceKey)
            .containsExactlyInAnyOrder("base-balance", "supplemental-balance");
        var selected = service.preview(new ActivationPreviewQuery("1010", warehouse, null, 0, 50, first, snapshotDate));
        assertThat(selected.items()).extracting(ActivationPreviewCandidate::sourceKey).containsExactly("base-balance");
    }

    @Test
    void defaultPreviewRejectsDuplicatePositionsAcrossSnapshotImports() {
        TenantContext.setTenant(tenant);
        CompanyContext.setCompany(company);
        UUID warehouse = UUID.randomUUID(), material = UUID.randomUUID();
        UUID first = insertImport(snapshotDate, "duplicate-base");
        UUID second = insertImport(snapshotDate, "duplicate-addendum");
        insertBalance(first, "first-position", snapshotDate, material, warehouse, "5");
        insertBalance(second, "second-position", snapshotDate, material, warehouse, "7");
        completeImport(first);
        completeImport(second);
        var service = new ActivationPreviewService(new NamedParameterJdbcTemplate(dataSource));
        var result = service.preview(new ActivationPreviewQuery("1010", warehouse, null, 0, 50));
        assertThat(result.items()).hasSize(2);
        assertThat(result.items()).extracting(ActivationPreviewCandidate::classification)
            .containsOnly(SourcePositionClassification.UNRESOLVED);
        assertThat(result.items()).allSatisfy(item -> assertThat(item.blockedReason()).contains("Multiple source rows"));
    }

    @Test
    void finishedGoodsPreviewNetsSourceAccountsWithoutIncludingOutputOrOtherCompanies() {
        TenantContext.setTenant(tenant);
        CompanyContext.setCompany(company);
        UUID material = UUID.randomUUID(), warehouse = UUID.randomUUID();
        UUID source = insertImport(snapshotDate, "goods-source");
        insertGoodsBalance(source, "goods-2812", 1, material, warehouse, "2812", "7212.3330360", "89139138603.85");
        insertGoodsBalance(source, "goods-2813", 2, material, warehouse, "2813", "-1764.0000000", "0");
        insertGoodsBalance(source, "goods-output", 3, material, warehouse, "2840", "-100", "0");
        completeImport(source);
        var reader = new FinishedGoodsOpeningReader(new NamedParameterJdbcTemplate(dataSource));
        var result = reader.preview(tenant, company, snapshotDate, warehouse);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().quantity()).isEqualByComparingTo("5448.333036");
        assertThat(result.getFirst().amount()).isEqualByComparingTo("89139138603.85");
        assertThat(result.getFirst().sourceKeys()).containsExactly("goods-2812", "goods-2813");
        assertThatThrownBy(() -> reader.preview(tenant, UUID.randomUUID(), snapshotDate, warehouse))
            .isInstanceOf(BadRequestException.class);
    }

    @Test
    void finishedGoodsAreBlockedForAccountReconciliationRatherThanReportedAsNonOwned() {
        TenantContext.setTenant(tenant);
        CompanyContext.setCompany(company);
        UUID warehouse = UUID.randomUUID();
        UUID source = insertImport(snapshotDate, "goods-category");
        insertGoodsBalance(source, "goods-2812-category", 1, UUID.randomUUID(), warehouse,
            "2812", "1", "2");
        completeImport(source);
        var result = new ActivationPreviewService(new NamedParameterJdbcTemplate(dataSource))
            .preview(new ActivationPreviewQuery("2812", warehouse, null, 0, 50));
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().classification()).isEqualTo(SourcePositionClassification.UNRESOLVED);
        assertThat(result.items().getFirst().blockedReason()).contains("netting");
    }

    @Test
    void completedImportCannotAcquireEntriesFromAnActiveImport() {
        UUID completed = insertImport(snapshotDate, "completed-target");
        completeImport(completed);
        UUID active = insertImport(snapshotDate, "active-origin");
        insertBalance(active, "active-row", snapshotDate, UUID.randomUUID(), UUID.randomUUID(), "1");
        assertThatThrownBy(() -> jdbc.update(
            "update warehouse_account_entries set import_id=? where import_id=?", completed, active))
            .isInstanceOf(org.springframework.dao.DataAccessException.class)
            .hasMessageContaining("immutable");
    }

    @Test
    void approvalActorCannotBeRewrittenAfterApproval() {
        UUID planId = UUID.randomUUID();
        jdbc.update("""
            insert into warehouse_activation_plans
            (id,tenant_id,company_id,plan_code,posting_date,preview_hash,source_content_hash,status,
             item_count,total_quantity,total_amount,approved_by,approved_at)
            values (?,?,?,'immutable-plan',?,?,?,'APPROVED',0,0,0,'finance-a',current_timestamp)
            """, planId, tenant, company, snapshotDate, "a".repeat(64), "b".repeat(64));
        assertThatThrownBy(() -> jdbc.update(
            "update warehouse_activation_plans set approved_by='finance-b' where id=?", planId))
            .isInstanceOf(org.springframework.dao.DataAccessException.class)
            .hasMessageContaining("immutable");
    }

    @Test
    void postSnapshotSourceMovementBlocksAutomaticActivation() {
        TenantContext.setTenant(tenant);
        CompanyContext.setCompany(company);
        UUID importId = insertImport(snapshotDate, "movement");
        UUID material = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        insertSourceEntry(importId, "balance-row", "BALANCE", 1, snapshotDate,
            material, warehouse, "8.0000000");
        insertSourceEntry(importId, "movement-row", "MOVEMENT", 2, snapshotDate.plusDays(1),
            material, warehouse, "1.0000000");
        completeImport(importId);

        var service = new ActivationPreviewService(new NamedParameterJdbcTemplate(dataSource));
        var result = service.preview(new ActivationPreviewQuery("1010", warehouse, null,
            0, 50, importId, snapshotDate.plusDays(1)));
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).classification()).isEqualTo(SourcePositionClassification.UNRESOLVED);
        assertThat(result.items().get(0).blockedReason()).contains("Post-snapshot");
    }

    @Test
    void missingPhysicalStockIsNotHiddenByCommittedPostingEvidence() {
        TenantContext.setTenant(tenant);
        CompanyContext.setCompany(company);
        UUID importId = insertImport(snapshotDate, "physical-gap");
        UUID material = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        insertBalance(importId, "balance-gap", snapshotDate, material, warehouse, "10.0000000");
        UUID rule = UUID.randomUUID();
        UUID batch = UUID.randomUUID();
        jdbc.update("""
            insert into warehouse_posting_rules
            (id,tenant_id,company_id,book,operation,context_key,rule_version,effective_from,
             approved,account_code,currency_code,rule_snapshot,rule_hash)
            values (?,?,?,'WAREHOUSE','RECEIPT','OPENING',1,?,true,'1010','UZS','{}'::jsonb,?)
            """, rule, tenant, company, snapshotDate, "c".repeat(64));
        jdbc.update("""
            insert into warehouse_posting_batches
            (id,tenant_id,company_id,book,operation,context_key,resource_id,idempotency_key,
             request_hash,posting_date,expected_version,rule_id,rule_version,rule_snapshot,status)
            values (?,?,?,'WAREHOUSE','RECEIPT','OPENING',?,?,?, ?,1,?,1,'{}'::jsonb,'PENDING')
            """, batch, tenant, company, material, "physical-gap", "d".repeat(64), snapshotDate, rule);
        jdbc.update("""
            insert into warehouse_posting_effects
            (batch_id,effect_sequence,effect_type,material_id,warehouse_id,account_code,
             base_unit_code,quantity,amount,currency_code,stock_category)
            values (?,1,'STOCK',?,?,'1010','PCS',10,0,'UZS','OWNED'),
                   (?,2,'ACCOUNT',?,?,'1010','PCS',10,1,'UZS','OWNED')
            """, batch, material, warehouse, batch, material, warehouse);
        jdbc.update("update warehouse_posting_batches set status='COMMITTED', receipt_json='{}'::jsonb, committed_at=current_timestamp where id=?", batch);
        jdbc.update("update warehouse_account_imports set status='COMPLETED', completed_at=current_timestamp + interval '1 minute' where id=?", importId);

        var result = new ActivationPreviewService(new NamedParameterJdbcTemplate(dataSource))
            .preview(new ActivationPreviewQuery("1010", warehouse, null, 0, 50, importId, snapshotDate));
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().classification()).isEqualTo(SourcePositionClassification.UNRESOLVED);
        assertThat(result.items().getFirst().blockedReason()).contains("Physical stock and committed posting evidence differ");
    }

    @Test
    void accountPositionLockSerializesAcrossPhysicalBins() throws Exception {
        UUID material = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        var store = new JdbcPostingEvidenceStore(jdbc, new ObjectMapper());
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var holder = executor.submit(() -> transaction.execute(status -> {
                store.lockAccountPosition(tenant, company, material, warehouse, "1010", null, "OWNED");
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return null;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            String key = String.join("\u001f", tenant, company.toString(), material.toString(),
                warehouse.toString(), "1010", company.toString(), "OWNED");
            Boolean secondLockAcquired = transaction.execute(status -> jdbc.queryForObject(
                "select pg_try_advisory_xact_lock(hashtextextended(?, 0))", Boolean.class, key));
            assertThat(secondLockAcquired).isFalse();
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void differentKeysCannotApplyOneApprovedPlanTwice() throws Exception {
        UUID importId = insertImport(snapshotDate, "concurrent");
        completeImport(importId);
        UUID warehouse = UUID.randomUUID();
        UUID material = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        var candidate = new ActivationPreviewCandidate("opening-row", "1010", "Materials",
            material, "MAT", "Material", warehouse, "WH", "Warehouse", "OWNED", "PCS",
            new BigDecimal("8.0000000"), new BigDecimal("1.0000"), BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("8.0000000"), new BigDecimal("1.0000"),
            SourcePositionClassification.ELIGIBLE_DELTA, "b".repeat(64), null);
        var preview = new ActivationPreviewResult(1, 1, 0, 0, 0, 0, 0,
            candidate.sourceQuantity(), candidate.sourceAmount(), candidate.deltaQuantity(),
            candidate.deltaAmount(), List.of(candidate), 0, 50);
        ActivationPreviewService previewService = mock(ActivationPreviewService.class);
        when(previewService.preview(any())).thenReturn(preview);
        AccountAwareStockPostingService postingService = mock(AccountAwareStockPostingService.class);
        ActivationApplyService service = new ActivationApplyService(previewService, postingService, jdbc);
        String previewHash = service.computePreviewHash(List.of(candidate));
        String sourceHash = service.computeSourceContentHash(List.of(candidate));
        jdbc.update("""
            insert into warehouse_activation_plans
            (id,tenant_id,company_id,plan_code,account_code,posting_date,preview_hash,
             source_content_hash,status,item_count,total_quantity,total_amount,
             source_import_id,source_snapshot_date,source_manifest_sha256,cutover_date)
            values (?,?,?,'concurrent-plan','1010',?,?,?,'APPROVED',1,8,1,?,?,?,?)
            """, planId, tenant, company, postingDate, previewHash, sourceHash,
            importId, snapshotDate, manifestHash("concurrent"), snapshotDate);
        jdbc.update("""
            insert into warehouse_posting_rules
            (id,tenant_id,company_id,book,operation,context_key,rule_version,effective_from,
             approved,account_code,currency_code,rule_snapshot,rule_hash)
            values (?,?,?,'WAREHOUSE','RECEIPT','OPENING',1,?,true,'1010','UZS','{}'::jsonb,?)
            """, UUID.randomUUID(), tenant, company, snapshotDate, "c".repeat(64));

        CountDownLatch postingEntered = new CountDownLatch(1);
        CountDownLatch releasePosting = new CountDownLatch(1);
        when(postingService.postReceipt(any())).thenAnswer(invocation -> {
            var request = (AccountAwareStockPostingService.ReceiptRequest) invocation.getArgument(0);
            jdbc.update("insert into activation_test_postings(plan_id,command_key) values (?,?)",
                planId, request.command().idempotencyKey());
            postingEntered.countDown();
            if (!releasePosting.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release posting");
            }
            return new PostingReceipt(UUID.randomUUID(), UUID.randomUUID(), 1, postingDate,
                List.of(new PostingEffect(PostingEffectType.STOCK, material, warehouse, "1010", "PCS",
                    candidate.deltaQuantity(), BigDecimal.ZERO, null)));
        });

        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> applyInTransaction(transaction, service, planId, "first-key"));
            if (!postingEntered.await(10, TimeUnit.SECONDS)) {
                Object earlyResult = first.get(2, TimeUnit.SECONDS);
                if (earlyResult instanceof Throwable failure) {
                    throw new AssertionError("First apply failed before posting", failure);
                }
                throw new AssertionError("First apply returned without posting: " + earlyResult);
            }
            var second = executor.submit(() -> applyInTransaction(transaction, service, planId, "second-key"));
            Thread.sleep(150);
            releasePosting.countDown();
            Object firstResult = first.get(15, TimeUnit.SECONDS);
            Object secondResult = second.get(15, TimeUnit.SECONDS);
            assertThat(firstResult).isInstanceOf(ActivationApplyService.ActivationApplyResult.class);
            assertThat(secondResult).isInstanceOf(BadRequestException.class);
        } finally {
            releasePosting.countDown();
        }
        assertThat(jdbc.queryForObject("select count(*) from activation_test_postings where plan_id=?",
            Integer.class, planId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from warehouse_activation_journals where plan_id=?",
            Integer.class, planId)).isEqualTo(1);
        Object replay = applyInTransaction(transaction, service, planId, "first-key");
        assertThat(replay).isInstanceOf(ActivationApplyService.ActivationApplyResult.class);
        assertThat(jdbc.queryForObject("select count(*) from activation_test_postings where plan_id=?",
            Integer.class, planId)).isEqualTo(1);
    }

    private Object applyInTransaction(TransactionTemplate transaction, ActivationApplyService service,
                                      UUID planId, String key) {
        TenantContext.setTenant(tenant);
        CompanyContext.setCompany(company);
        try {
            return transaction.execute(status -> service.apply(new ActivationApplyService.ActivationApplyRequest(
                key, "1010", null, postingDate, planId)));
        } catch (RuntimeException failure) {
            return failure;
        } finally {
            TenantContext.clear();
            CompanyContext.clear();
        }
    }

    private UUID insertImport(LocalDate date, String organization) {
        UUID importId = UUID.randomUUID();
        jdbc.update("""
            insert into warehouse_account_imports
            (id,tenant_id,company_id,organization_source_id,snapshot_date,manifest_sha256,
             expected_counts,expected_records,accepted_records,expected_chain,chain_sha256,status,completed_at)
            values (?,?,?,?,? ,?,'{}'::jsonb,0,0,?,?,'IMPORTING',null)
            """, importId, tenant, company, organization, date,
            manifestHash(organization), "d".repeat(64), "d".repeat(64));
        return importId;
    }

    private void completeImport(UUID importId) {
        jdbc.update("update warehouse_account_imports set status='COMPLETED', completed_at=current_timestamp where id=?",
            importId);
    }

    private String manifestHash(String organization) {
        return String.format("%064x", organization.hashCode() & 0xffffffffL);
    }

    private void insertBalance(UUID importId, String key, LocalDate date, UUID material,
                               UUID warehouse, String quantity) {
        insertSourceEntry(importId, key, "BALANCE", 1, date, material, warehouse, quantity);
    }

    private void insertGoodsBalance(UUID importId, String key, int sequence, UUID material, UUID warehouse,
                                    String account, String quantity, String amount) {
        jdbc.update("""
            insert into warehouse_account_entries
            (id,tenant_id,company_id,import_id,source_seq,source_key,kind,source_date,
             account_source_id,account_code,account_name,material_reference_type,
             material_source_id,warehouse_source_id,material_id,warehouse_id,
             material_code,material_name,warehouse_code,warehouse_name,source_type,
             source_material_type,stock_category,uom,uom_name_ru,quantity,amount,
             additional_dimensions,row_sha256,content)
            values (?,?,?,?,?,?,'BALANCE',?,'acct',?,'Goods','00000037','mat-src','wh-src',?,?,'M','Product',
                    'W','Warehouse','FINISHED_GOODS','Product','FINISHED_PRODUCTS','TON','tonnes',?,?,
                    '{}'::jsonb,?,'{}')
            """, UUID.randomUUID(), tenant, company, importId, sequence, key, snapshotDate, account,
            material, warehouse, new BigDecimal(quantity), new BigDecimal(amount), "b".repeat(64));
    }

    private void insertSourceEntry(UUID importId, String key, String kind, int sequence, LocalDate date,
                                   UUID material, UUID warehouse, String quantity) {
        jdbc.update("""
            insert into warehouse_account_entries
            (id,tenant_id,company_id,import_id,source_seq,source_key,kind,source_date,
             account_source_id,account_code,account_name,material_reference_type,
             material_source_id,warehouse_source_id,material_id,warehouse_id,
             material_code,material_name,warehouse_code,warehouse_name,source_type,
             source_material_type,stock_category,uom,uom_name_ru,quantity,amount,
             additional_dimensions,row_sha256,content)
            values (? ,? ,? ,?,?,?,? ,?,'acct','1010','Materials','MATERIAL',
                    'mat-src','wh-src',?,?,'MAT','Material','WH','Warehouse','MATERIAL',
                    'RAW','OWNED','PCS','pieces',?,1.0000,'{}'::jsonb,?,
                    '{"currencyCode":"UZS"}')
            """, UUID.randomUUID(), tenant, company, importId, sequence, key, kind, date, material, warehouse,
            new BigDecimal(quantity), "b".repeat(64));
    }
}
