package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.MeasurementUnitType;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.ExactOpeningStockDtos.ChunkRequest;
import com.enterprise.mm.payload.ExactOpeningStockDtos.Row;
import com.enterprise.mm.payload.ExactOpeningStockDtos.RowResult;
import com.enterprise.mm.payload.ExactOpeningStockDtos.StartRequest;
import com.enterprise.mm.payload.UnitOfMeasureUpdateRequest;
import com.enterprise.mm.payload.StockMovementCreateRequest;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Exercises the production migrations and committed stock evidence in an isolated PostgreSQL database. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureObservability
@EntityScan(basePackages = {
    "com.enterprise.mm.entity", "com.enterprise.mm.procurement.command.entity",
    "com.enterprise.mm.supply.workspace.entity", "com.enterprise.mm.common",
    "com.enterprise.mm.warehouse"
})
@Testcontainers(disabledWithoutDocker = false)
@TestPropertySource(properties = {
    "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
    "app.seed.enabled=false", "mm.pilot.enabled-capabilities="
})
class ExactOpeningStockPostgresTest {
    private static final String TENANT = "default";
    private static final String SOURCE_HASH = "a".repeat(64);
    private static final String PREPARED_HASH = "b".repeat(64);
    private static final String SOURCE_ORG = "af14001b2423947111dd5d6ef8f82783";
    private static final String SOURCE_MATERIAL = "c".repeat(32);
    private static final String SOURCE_WAREHOUSE = "d".repeat(32);
    private static final LocalDate POSTING_DATE = LocalDate.of(2026, 10, 7);
    private static final List<String> STOCK_TABLES = List.of(
        "stock_movements", "stock_batches", "stock_balances", "inventory_cost_layers",
        "stock_cost_movements", "stock_movement_allocations", "stock_movement_allocation_cost_layers");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("mm_exact_opening_test").withUsername("mm").withPassword("mm");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired ExactOpeningStockService opening;
    @Autowired FinishedGoodsOpeningMigrationService finishedGoodsOpening;
    @Autowired IssueAccountingReviewService issueAccountingReview;
    @Autowired StockMovementService stock;
    @Autowired StockCostLedgerService ledger;
    @Autowired InventoryValuationReconciliationService reconciliation;
    @Autowired InventoryFinanceCalculationService finance;
    @Autowired UnitOfMeasureService unitService;
    @Autowired CompanyRepository companies;
    @Autowired MaterialRepository materials;
    @Autowired MaterialGroupRepository groups;
    @Autowired UnitOfMeasureRepository units;
    @Autowired WarehouseRepository warehouses;
    @Autowired StorageLocationRepository locations;
    @Autowired JdbcTemplate jdbc;
    @SpyBean InventoryCostingService costing;

    @BeforeEach
    void setContext() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "exact-opening.test", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                new SimpleGrantedAuthority("ROLE_MM_MANAGER"), new SimpleGrantedAuthority("ROLE_WAREHOUSE_MANAGER"))));
    }

    @AfterEach
    void clearContext() {
        reset(costing);
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void exactRowsRetainSevenPlacesZeroValueAndSeparateAccountsWithDurableReplay() {
        Fixture f = fixture();
        UUID run = UUID.randomUUID();
        StartRequest start = start(run, 3, "3.1000008", "13.34");
        assertThat(opening.start(TENANT, f.company(), start).status()).isEqualTo("IMPORTING");
        assertThat(opening.start(TENANT, f.company(), start).importId()).isEqualTo(run);
        ChunkRequest chunk = new ChunkRequest(UUID.randomUUID(), List.of(
            row(f, '1', "1010", "3", "1.00"),
            row(f, '2', "1040", "0.0000007", "0.00"),
            row(f, '3', "1090", "0.1000001", "12.34")));

        var posted = opening.postChunk(TENANT, f.company(), run, chunk);
        var replay = opening.postChunk(TENANT, f.company(), run, chunk);
        assertThat(replay.rows()).isEqualTo(posted.rows());
        assertThat(posted.rows()).extracting(RowResult::stockBatchId).doesNotHaveDuplicates();
        for (RowResult result : posted.rows()) assertEvidence(f, result);
        assertCompanyStockCounts(f.company(), 3);
        assertDecimal(companySum("stock_balances", "quantity", f.company()), "3.1000008");
        assertDecimal(companySum("stock_balances", "total_value", f.company()), "13.34");
        assertThat(zeroInboundCount(f)).isEqualTo(1);

        assertThatThrownBy(() -> opening.postChunk(TENANT, f.company(), run,
            new ChunkRequest(UUID.randomUUID(), List.of(chunk.rows().getFirst()))))
            .isInstanceOf(ConflictException.class).hasMessageContaining("already posted");
        Row changed = row(f, '1', "1010", "3", "1.01");
        assertThatThrownBy(() -> opening.postChunk(TENANT, f.company(), run,
            new ChunkRequest(chunk.chunkId(), List.of(changed))))
            .isInstanceOf(ConflictException.class).hasMessageContaining("different request payload");

        var completed = opening.complete(TENANT, f.company(), run);
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.acceptedRows()).isEqualTo(3);
        assertDecimal(completed.acceptedQuantityChecksum(), "3.1000008");
        assertDecimal(completed.acceptedValueUzs(), "13.34");
        assertThat(opening.complete(TENANT, f.company(), run).status()).isEqualTo("COMPLETED");
        assertThat(opening.postChunk(TENANT, f.company(), run, chunk).rows()).isEqualTo(posted.rows());
        assertThat(reconciliation.blockerIssues(f.warehouse().getId())).isEmpty();
        assertThat(zeroInboundCount(f)).isZero();
        UUID zeroBatch = posted.rows().get(1).stockBatchId();
        jdbc.update("update inventory_cost_layers set authoritative_value=false where stock_batch_id=?", zeroBatch);
        assertThat(zeroInboundCount(f)).isEqualTo(1);
        jdbc.update("update inventory_cost_layers set authoritative_value=true where stock_batch_id=?", zeroBatch);
        assertThat(zeroInboundCount(f)).isZero();
        UnitOfMeasureUpdateRequest lowerPrecision = unitUpdate(f);
        lowerPrecision.setStockQuantityScale(4);
        assertThatThrownBy(() -> unitService.update(f.unit().getId(), lowerPrecision, Locale.ENGLISH))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("precision cannot be reduced");
        assertThat(units.findById(f.unit().getId()).orElseThrow().getStockQuantityScale()).isEqualTo(7);
        unitService.update(f.unit().getId(), unitUpdate(f), Locale.ENGLISH);
        assertThat(units.findById(f.unit().getId()).orElseThrow().getStockQuantityScale()).isEqualTo(7);
    }

    @Test
    void postedIssueCreatesAccountingReviewWithoutChangingStockTwice() {
        Fixture f = fixture();
        UUID run = UUID.randomUUID();
        opening.start(TENANT, f.company(), start(run, 1, "3", "30"));
        opening.postChunk(TENANT, f.company(), run,
            new ChunkRequest(UUID.randomUUID(), List.of(row(f, '1', "1010", "3", "30"))));
        opening.complete(TENANT, f.company(), run);
        var issued = stock.createAndPost(ordinary(f, MovementType.ISSUE, "1", "0"));
        assertThat(jdbc.queryForObject("""
            select status from stock_issue_account_reviews
            where tenant_id=? and company_id=? and movement_id=?
            """, String.class, TENANT, f.company(), issued.getId())).isEqualTo("PENDING_REVIEW");
        assertDecimal(companySum("stock_balances", "quantity", f.company()), "2");
        assertDecimal(companySum("stock_balances", "total_value", f.company()), "20");
    }

    @Test
    void accountantCanResolveIssueAccountsOnceWithoutPostingAnotherStockMovement() {
        Fixture f = fixture();
        UUID run = UUID.randomUUID();
        opening.start(TENANT, f.company(), start(run, 1, "3", "30"));
        opening.postChunk(TENANT, f.company(), run,
            new ChunkRequest(UUID.randomUUID(), List.of(row(f, '1', "1010", "3", "30"))));
        opening.complete(TENANT, f.company(), run);
        var issued = stock.createAndPost(ordinary(f, MovementType.ISSUE, "1", "0"));
        UUID reviewId = jdbc.queryForObject("select id from stock_issue_account_reviews where movement_id=?",
            UUID.class, issued.getId());
        jdbc.update("update stock_movements set account_code_snapshot='2810' where id=?", issued.getId());
        var named = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
        var reports = new com.enterprise.mm.warehouse.reports.WarehouseReportQuery(named);
        var params = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("tenant", TENANT)
            .addValue("company", f.company()).addValue("id", issued.getId());
        assertThat(named.queryForMap(reports.ledger() + " select account_code_snapshot from enriched where id=:id", params))
            .containsEntry("account_code_snapshot", null);
        assertThat(issueAccountingReview.list("PENDING_REVIEW", 0, 20).content())
            .anySatisfy(row -> assertThat(row).containsEntry("movementId", issued.getId()));
        for (String code : List.of("2812", "9112"))
            jdbc.update("""
                insert into chart_of_accounts(id,tenant_id,company_id,created_at,name,status,account_code,is_group,is_delete)
                values (?,?,?,current_timestamp,?,'ACTIVE',?,false,false)
                """, UUID.randomUUID(), TENANT, f.company(), "Account " + code, code);
        assertThatThrownBy(() -> issueAccountingReview.resolve(reviewId, "missing", "9112", "Source"))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("active posting account");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "warehouse.manager", "n/a", List.of(new SimpleGrantedAuthority("ROLE_WAREHOUSE_MANAGER"))));
        assertThatThrownBy(() -> issueAccountingReview.resolve(reviewId, "2812", "9112", "Source"))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "accountant", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ACCOUNTANT"))));
        issueAccountingReview.resolve(reviewId, "2812", "9112", "1C source evidence");
        issueAccountingReview.resolve(reviewId, "2812", "9112", "1C source evidence");
        assertThat(jdbc.queryForMap("select status,stock_account_code,counter_account_code from stock_issue_account_reviews where id=?", reviewId))
            .containsEntry("status", "RESOLVED").containsEntry("stock_account_code", "2812").containsEntry("counter_account_code", "9112");
        var report = named.queryForMap(reports.ledger() + " select account_code_snapshot,counter_account_snapshot from enriched where id=:id",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("tenant", TENANT)
                .addValue("company", f.company()).addValue("id", issued.getId()));
        assertThat(report).containsEntry("account_code_snapshot", "2812").containsEntry("counter_account_snapshot", "9112");
        assertThatThrownBy(() -> issueAccountingReview.resolve(reviewId, "2812", "9113", "changed"))
            .isInstanceOf(ConflictException.class);
        assertDecimal(companySum("stock_balances", "quantity", f.company()), "2");
        assertDecimal(companySum("stock_balances", "total_value", f.company()), "20");
        assertThat(count("select count(*) from stock_movements where tenant_id=? and company_id=?", TENANT, f.company())).isEqualTo(2);
    }

    @Test
    void cancelledPostedIssueRemainsAvailableForAccountingReview() {
        Fixture f = fixture();
        UUID run = UUID.randomUUID();
        opening.start(TENANT, f.company(), start(run, 1, "3", "30"));
        opening.postChunk(TENANT, f.company(), run,
            new ChunkRequest(UUID.randomUUID(), List.of(row(f, '1', "1010", "3", "30"))));
        opening.complete(TENANT, f.company(), run);
        var issued = stock.createAndPost(ordinary(f, MovementType.ISSUE, "1", "0"));
        UUID review = jdbc.queryForObject("select id from stock_issue_account_reviews where movement_id=?", UUID.class, issued.getId());
        jdbc.update("update stock_movements set status='CANCELLED' where id=?", issued.getId());
        assertThat(issueAccountingReview.list("PENDING_REVIEW", 0, 20).content())
            .anySatisfy(row -> assertThat(row).containsEntry("movementId", issued.getId()));
        for (String code : List.of("2812", "9112"))
            jdbc.update("""
                insert into chart_of_accounts(id,tenant_id,company_id,created_at,name,status,account_code,is_group,is_delete)
                values (?,?,?,current_timestamp,?,'ACTIVE',?,false,false)
                """, UUID.randomUUID(), TENANT, f.company(), "Account " + code, code);
        issueAccountingReview.resolve(review, "2812", "9112", "Review of reversed issue");
    }

    @Test
    void supplementalFinishedGoodsUseExactNetValueAndReplayWithoutDuplicateStock() {
        Fixture f = fixture();
        UUID originalRun = UUID.randomUUID();
        opening.start(TENANT, f.company(), start(originalRun, 1, "1", "1.00"));
        opening.postChunk(TENANT, f.company(), originalRun,
            new ChunkRequest(UUID.randomUUID(), List.of(row(f, '1', "1010", "1", "1.00"))));
        opening.complete(TENANT, f.company(), originalRun);

        Material product = new Material();
        product.setCode("FG-" + UUID.randomUUID().toString().substring(0, 8));
        product.setName("Finished product"); product.setStatus("ACTIVE"); product.setMaterialType(MaterialType.TMB);
        product.setMaterialSubtype(com.enterprise.mm.enums.MaterialSubtype.FINISHED_GOODS);
        product.setUnitOfMeasureId(f.unit().getId()); product = materials.saveAndFlush(product);
        insertFinishedGoodsSource(f, product.getId());
        insertFinishedGoodsLinks(f, product.getId());
        var preview = finishedGoodsOpening.preview(POSTING_DATE, f.warehouse().getId());
        assertThat(preview.entries()).hasSize(1);
        assertThat(preview.entries().getFirst().status()).isEqualTo("ELIGIBLE");
        assertThat(preview.entries().getFirst().quantity()).isEqualByComparingTo("5448.3330360");
        assertThat(preview.entries().getFirst().amount()).isEqualByComparingTo("89139138603.85");
        var evidence = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(preview.entries().getFirst());
        assertThat(evidence.path("accountBreakdown").isArray()).isTrue();
        assertThat(evidence.path("accountBreakdown")).hasSize(2);
        assertThat(evidence.path("accountBreakdown").get(0).path("accountCode").asText()).isEqualTo("2812");
        assertThat(evidence.path("accountBreakdown").get(1).path("accountCode").asText()).isEqualTo("2813");

        var result = finishedGoodsOpening.apply(POSTING_DATE, f.warehouse().getId(), preview.hash(), "fg-opening-test");
        assertThat(result.postedCount()).isEqualTo(1);
        assertThat(finishedGoodsOpening.apply(POSTING_DATE, f.warehouse().getId(), preview.hash(), "fg-opening-test"))
            .isEqualTo(result);
        assertThatThrownBy(() -> finishedGoodsOpening.apply(POSTING_DATE, f.warehouse().getId(),
            "0".repeat(64), "fg-opening-test"))
            .isInstanceOf(ConflictException.class).hasMessageContaining("different input");
        assertCompanyStockCounts(f.company(), 2);
        assertThat(count("select count(*) from finished_goods_opening_positions where company_id=?", f.company())).isEqualTo(1);
        assertDecimal(companySum("stock_balances", "quantity", f.company()), "5449.3330360");
        assertDecimal(companySum("stock_balances", "total_value", f.company()), "89139138604.85");
        StockMovement issue = ordinary(f, MovementType.ISSUE, "1", "0");
        issue.setMaterialId(product.getId());
        issue.setStorageLocationId(null);
        UUID movement = stock.createAndPost(issue).getId();
        assertThat(issueAccountingReview.list("PENDING_REVIEW", 0, 20).content())
            .anySatisfy(review -> assertThat(review).containsEntry("movementId", movement)
                .containsEntry("sourceAccountCodes", "2812, 2813"));
    }

    @Test
    void supplementalFinishedGoodsRejectStalePreviewWhenStockChanges() {
        Fixture f = fixture();
        UUID originalRun = UUID.randomUUID();
        opening.start(TENANT, f.company(), start(originalRun, 1, "1", "1.00"));
        opening.postChunk(TENANT, f.company(), originalRun,
            new ChunkRequest(UUID.randomUUID(), List.of(row(f, '1', "1010", "1", "1.00"))));
        opening.complete(TENANT, f.company(), originalRun);

        Material product = new Material();
        product.setCode("FG-" + UUID.randomUUID().toString().substring(0, 8));
        product.setName("Finished product"); product.setStatus("ACTIVE"); product.setMaterialType(MaterialType.TMB);
        product.setMaterialSubtype(com.enterprise.mm.enums.MaterialSubtype.FINISHED_GOODS);
        product.setUnitOfMeasureId(f.unit().getId()); product = materials.saveAndFlush(product);
        insertFinishedGoodsSource(f, product.getId());
        insertFinishedGoodsLinks(f, product.getId());
        var preview = finishedGoodsOpening.preview(POSTING_DATE, f.warehouse().getId());
        assertThat(preview.entries().getFirst().status()).isEqualTo("ELIGIBLE");
        UUID historyId = UUID.randomUUID();
        jdbc.update("""
            insert into warehouse_imported_movements(id,tenant_id,company_id,document_key,movement_date,
              material_id,warehouse_id,material_source_id,warehouse_source_id,quantity,amount,source_record_id,source_records,movement_type)
            values(?,?,?,'later-issue',?,?,?,?,?,-1,-1,?,1,'ISSUE')
            """, historyId, TENANT, f.company(), POSTING_DATE.plusDays(1), product.getId(), f.warehouse().getId(),
            SOURCE_MATERIAL, SOURCE_WAREHOUSE, UUID.randomUUID());
        assertThat(finishedGoodsOpening.preview(POSTING_DATE, f.warehouse().getId()).entries().getFirst().status())
            .isEqualTo("SOURCE_ACTIVITY_REVIEW");
        assertThatThrownBy(() -> finishedGoodsOpening.apply(POSTING_DATE, f.warehouse().getId(), preview.hash(), "stale-history"))
            .isInstanceOf(ConflictException.class).hasMessageContaining("changed");
        jdbc.update("delete from warehouse_imported_movements where id=?", historyId);
        UUID historyRun = UUID.randomUUID();
        jdbc.update("""
            insert into warehouse_history_runs(id,tenant_id,company_id,manifest_sha256,organization_source_id,
              expected_counts,expected_records,expected_chain,chain_sha256,status)
            values(?,?,?,?,?,'{}',0,?,?,'COMPLETED')
            """, historyRun, TENANT, f.company(), "f".repeat(64), SOURCE_ORG, "0".repeat(64), "0".repeat(64));
        jdbc.update("""
            insert into warehouse_history_records(id,tenant_id,company_id,import_id,source_seq,kind,source_key,source_table,
              document_date,posted,material_id,warehouse_id,row_sha256,content)
            values(?,?,?,?,1,'MOVEMENT','later-history','_accrg4272',?,true,?,?,?,
              json_build_object('materialSourceId',?,'warehouseSourceId',?)::text)
            """, historyId, TENANT, f.company(), historyRun, POSTING_DATE.plusDays(1), product.getId(),
            f.warehouse().getId(), "f".repeat(64), SOURCE_MATERIAL, SOURCE_WAREHOUSE);
        assertThat(finishedGoodsOpening.preview(POSTING_DATE, f.warehouse().getId()).entries().getFirst().status())
            .isEqualTo("SOURCE_ACTIVITY_REVIEW");
        jdbc.update("""
            update warehouse_history_records set warehouse_id=null, posted=null,
              content=json_build_object('materialSourceId',?,'warehouseSourceId','another', 'targetWarehouseSourceId',?)::text
            where id=?
            """, SOURCE_MATERIAL, SOURCE_WAREHOUSE, historyId);
        assertThat(finishedGoodsOpening.preview(POSTING_DATE, f.warehouse().getId()).entries().getFirst().status())
            .isEqualTo("SOURCE_ACTIVITY_REVIEW");
        jdbc.update("delete from warehouse_history_records where id=?", historyId);
        StockMovement other = ordinary(f, MovementType.RECEIPT, "1", "1");
        other.setMaterialId(product.getId());
        stock.createAndPost(other);
        assertThatThrownBy(() -> finishedGoodsOpening.apply(POSTING_DATE, f.warehouse().getId(), preview.hash(), "stale"))
            .isInstanceOf(ConflictException.class).hasMessageContaining("changed");
        assertThat(count("select count(*) from finished_goods_opening_positions where company_id=?", f.company())).isZero();
    }

    @Test
    void supplementalFinishedGoodsRollBackStockAndJournalTogether() {
        Fixture f = fixture();
        UUID originalRun = UUID.randomUUID();
        opening.start(TENANT, f.company(), start(originalRun, 1, "1", "1.00"));
        opening.postChunk(TENANT, f.company(), originalRun,
            new ChunkRequest(UUID.randomUUID(), List.of(row(f, '1', "1010", "1", "1.00"))));
        opening.complete(TENANT, f.company(), originalRun);
        Material product = new Material();
        product.setCode("FG-" + UUID.randomUUID().toString().substring(0, 8));
        product.setName("Finished product"); product.setStatus("ACTIVE"); product.setMaterialType(MaterialType.TMB);
        product.setMaterialSubtype(com.enterprise.mm.enums.MaterialSubtype.FINISHED_GOODS);
        product.setUnitOfMeasureId(f.unit().getId()); product = materials.saveAndFlush(product);
        insertFinishedGoodsSource(f, product.getId());
        insertFinishedGoodsLinks(f, product.getId());
        var preview = finishedGoodsOpening.preview(POSTING_DATE, f.warehouse().getId());
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new BusinessException("Controlled failure after cost layer");
        }).when(costing).recordExactOpeningLayer(any(StockMovement.class), any(BigDecimal.class));
        try {
            assertThatThrownBy(() -> finishedGoodsOpening.apply(POSTING_DATE, f.warehouse().getId(), preview.hash(), "retry"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Controlled failure");
        } finally { reset(costing); }
        assertCompanyStockCounts(f.company(), 1);
        assertThat(count("select count(*) from finished_goods_opening_positions where company_id=?", f.company())).isZero();
        assertThat(count("select count(*) from finished_goods_opening_runs where company_id=?", f.company())).isZero();
        assertThat(finishedGoodsOpening.apply(POSTING_DATE, f.warehouse().getId(), preview.hash(), "retry").postedCount())
            .isEqualTo(1);
    }

    @Test
    void supplementalFinishedGoodsDoNotTrustUnverifiedDirectMasterIds() {
        Fixture f = fixture();
        UUID originalRun = UUID.randomUUID();
        opening.start(TENANT, f.company(), start(originalRun, 1, "1", "1.00"));
        opening.postChunk(TENANT, f.company(), originalRun,
            new ChunkRequest(UUID.randomUUID(), List.of(row(f, '1', "1010", "1", "1.00"))));
        opening.complete(TENANT, f.company(), originalRun);
        Material product = new Material();
        product.setCode("FG-" + UUID.randomUUID().toString().substring(0, 8));
        product.setName("Finished product"); product.setStatus("ACTIVE"); product.setMaterialType(MaterialType.TMB);
        product.setMaterialSubtype(com.enterprise.mm.enums.MaterialSubtype.FINISHED_GOODS);
        product.setUnitOfMeasureId(f.unit().getId()); product = materials.saveAndFlush(product);
        insertFinishedGoodsSource(f, product.getId());
        var preview = finishedGoodsOpening.preview(POSTING_DATE, f.warehouse().getId());
        assertThat(preview.entries().getFirst().status()).isEqualTo("MAPPING_REVIEW");
        assertThat(finishedGoodsOpening.apply(POSTING_DATE, f.warehouse().getId(), preview.hash(), "unverified").postedCount()).isZero();
        assertCompanyStockCounts(f.company(), 1);
    }

    @Test
    void supplementalFinishedGoodsRejectMismatchedSourceDimensions() {
        Fixture f = fixture();
        UUID originalRun = UUID.randomUUID();
        opening.start(TENANT, f.company(), start(originalRun, 1, "1", "1.00"));
        opening.postChunk(TENANT, f.company(), originalRun,
            new ChunkRequest(UUID.randomUUID(), List.of(row(f, '1', "1010", "1", "1.00"))));
        opening.complete(TENANT, f.company(), originalRun);
        Material product = new Material();
        product.setCode("FG-" + UUID.randomUUID().toString().substring(0, 8));
        product.setName("Finished product"); product.setStatus("ACTIVE"); product.setMaterialType(MaterialType.TMB);
        product.setMaterialSubtype(com.enterprise.mm.enums.MaterialSubtype.FINISHED_GOODS);
        product.setUnitOfMeasureId(f.unit().getId()); product = materials.saveAndFlush(product);
        insertFinishedGoodsSource(f, product.getId(), true);
        insertFinishedGoodsLinks(f, product.getId());
        var preview = finishedGoodsOpening.preview(POSTING_DATE, f.warehouse().getId());
        assertThat(preview.entries().getFirst().status()).isEqualTo("DIMENSIONS_REVIEW");
        assertThat(finishedGoodsOpening.apply(POSTING_DATE, f.warehouse().getId(), preview.hash(), "bad-dimensions").postedCount()).isZero();
        assertCompanyStockCounts(f.company(), 1);
    }

    @Test
    void supplementalFinishedGoodsRejectAnUnfinishedSourceImport() {
        Fixture f = fixture();
        UUID originalRun = UUID.randomUUID();
        opening.start(TENANT, f.company(), start(originalRun, 1, "1", "1.00"));
        opening.postChunk(TENANT, f.company(), originalRun,
            new ChunkRequest(UUID.randomUUID(), List.of(row(f, '1', "1010", "1", "1.00"))));
        opening.complete(TENANT, f.company(), originalRun);
        Material product = new Material();
        product.setCode("FG-" + UUID.randomUUID().toString().substring(0, 8));
        product.setName("Finished product"); product.setStatus("ACTIVE"); product.setMaterialType(MaterialType.TMB);
        product.setMaterialSubtype(com.enterprise.mm.enums.MaterialSubtype.FINISHED_GOODS);
        product.setUnitOfMeasureId(f.unit().getId()); product = materials.saveAndFlush(product);
        insertFinishedGoodsSource(f, product.getId());
        insertFinishedGoodsLinks(f, product.getId());
        var preview = finishedGoodsOpening.preview(POSTING_DATE, f.warehouse().getId());
        assertThat(preview.entries().getFirst().status()).isEqualTo("ELIGIBLE");
        jdbc.update("""
            insert into warehouse_account_imports
            (id,tenant_id,company_id,organization_source_id,snapshot_date,manifest_sha256,expected_counts,
             expected_records,accepted_records,expected_chain,chain_sha256,status)
            values (?,?,?,?,?,?,'{}'::jsonb,1,0,?,?,'IMPORTING')
            """, UUID.randomUUID(), TENANT, f.company(), SOURCE_ORG, POSTING_DATE,
            "e".repeat(64), "d".repeat(64), "0".repeat(64));
        assertThatThrownBy(() -> finishedGoodsOpening.apply(POSTING_DATE, f.warehouse().getId(), preview.hash(), "unfinished"))
            .isInstanceOf(ConflictException.class).hasMessageContaining("source import");
        assertCompanyStockCounts(f.company(), 1);
        assertThat(count("select count(*) from finished_goods_opening_positions where company_id=?", f.company())).isZero();
    }

    @Test
    void supplementalFinishedGoodsRejectSubsequentSourceMovement() {
        Fixture f = fixture();
        UUID originalRun = UUID.randomUUID();
        opening.start(TENANT, f.company(), start(originalRun, 1, "1", "1.00"));
        opening.postChunk(TENANT, f.company(), originalRun,
            new ChunkRequest(UUID.randomUUID(), List.of(row(f, '1', "1010", "1", "1.00"))));
        opening.complete(TENANT, f.company(), originalRun);
        Material product = new Material();
        product.setCode("FG-" + UUID.randomUUID().toString().substring(0, 8));
        product.setName("Finished product"); product.setStatus("ACTIVE"); product.setMaterialType(MaterialType.TMB);
        product.setMaterialSubtype(com.enterprise.mm.enums.MaterialSubtype.FINISHED_GOODS);
        product.setUnitOfMeasureId(f.unit().getId()); product = materials.saveAndFlush(product);
        insertFinishedGoodsSource(f, product.getId());
        insertFinishedGoodsLinks(f, product.getId());
        UUID laterImport = UUID.randomUUID();
        jdbc.update("""
            insert into warehouse_account_imports
            (id,tenant_id,company_id,organization_source_id,snapshot_date,manifest_sha256,expected_counts,
             expected_records,accepted_records,expected_chain,chain_sha256,status)
            values (?,?,?,?,?,?,'{}'::jsonb,0,0,?,?,'IMPORTING')
            """, laterImport, TENANT, f.company(), SOURCE_ORG, POSTING_DATE, "e".repeat(64), "d".repeat(64), "d".repeat(64));
        jdbc.update("""
            insert into warehouse_account_entries
            (id,tenant_id,company_id,import_id,source_seq,source_key,kind,source_date,
             account_source_id,account_code,account_name,material_reference_type,material_source_id,
             warehouse_source_id,material_id,warehouse_id,material_code,material_name,warehouse_code,warehouse_name,
             source_type,source_material_type,stock_category,uom,uom_name_ru,quantity,amount,
             additional_dimensions,row_sha256,content)
            select ?,tenant_id,company_id,?,1,'later-movement','MOVEMENT',?,account_source_id,account_code,
              account_name,material_reference_type,material_source_id,warehouse_source_id,material_id,warehouse_id,
              material_code,material_name,warehouse_code,warehouse_name,source_type,source_material_type,stock_category,
              uom,uom_name_ru,quantity,amount,additional_dimensions,row_sha256,content
            from warehouse_account_entries where tenant_id=? and company_id=? and source_key='fg-source-0'
            """, UUID.randomUUID(), laterImport, POSTING_DATE.plusDays(1), TENANT, f.company());
        jdbc.update("update warehouse_account_imports set status='COMPLETED',completed_at=current_timestamp where id=?", laterImport);
        var preview = finishedGoodsOpening.preview(POSTING_DATE, f.warehouse().getId());
        assertThat(preview.entries().getFirst().status()).isEqualTo("SOURCE_ACTIVITY_REVIEW");
        assertThat(finishedGoodsOpening.apply(POSTING_DATE, f.warehouse().getId(), preview.hash(), "source-activity").postedCount()).isZero();
        assertCompanyStockCounts(f.company(), 1);
    }

    @Test
    void supplementalFinishedGoodsRejectANewerSourceSnapshot() {
        Fixture f = fixture();
        UUID originalRun = UUID.randomUUID();
        opening.start(TENANT, f.company(), start(originalRun, 1, "1", "1.00"));
        opening.postChunk(TENANT, f.company(), originalRun,
            new ChunkRequest(UUID.randomUUID(), List.of(row(f, '1', "1010", "1", "1.00"))));
        opening.complete(TENANT, f.company(), originalRun);
        Material product = new Material();
        product.setCode("FG-" + UUID.randomUUID().toString().substring(0, 8));
        product.setName("Finished product"); product.setStatus("ACTIVE"); product.setMaterialType(MaterialType.TMB);
        product.setMaterialSubtype(com.enterprise.mm.enums.MaterialSubtype.FINISHED_GOODS);
        product.setUnitOfMeasureId(f.unit().getId()); product = materials.saveAndFlush(product);
        insertFinishedGoodsSource(f, product.getId());
        insertFinishedGoodsLinks(f, product.getId());
        var preview = finishedGoodsOpening.preview(POSTING_DATE, f.warehouse().getId());
        UUID newer = UUID.randomUUID();
        jdbc.update("""
            insert into warehouse_account_imports
            (id,tenant_id,company_id,organization_source_id,snapshot_date,manifest_sha256,expected_counts,
             expected_records,accepted_records,expected_chain,chain_sha256,status)
            values (?,?,?,?,?,?,'{}'::jsonb,0,0,?,?,'IMPORTING')
            """, newer, TENANT, f.company(), SOURCE_ORG, POSTING_DATE.plusDays(1),
            "e".repeat(64), "d".repeat(64), "d".repeat(64));
        jdbc.update("update warehouse_account_imports set status='COMPLETED',completed_at=current_timestamp where id=?", newer);
        assertThatThrownBy(() -> finishedGoodsOpening.apply(POSTING_DATE, f.warehouse().getId(), preview.hash(), "newer"))
            .isInstanceOf(ConflictException.class).hasMessageContaining("newer source snapshot");
        assertCompanyStockCounts(f.company(), 1);
    }

    private void insertFinishedGoodsSource(Fixture f, UUID material) {
        insertFinishedGoodsSource(f, material, false);
    }

    private void insertFinishedGoodsSource(Fixture f, UUID material, boolean mismatchedDimensions) {
        UUID source = UUID.randomUUID();
        jdbc.update("""
            insert into warehouse_account_imports
            (id,tenant_id,company_id,organization_source_id,snapshot_date,manifest_sha256,expected_counts,
             expected_records,accepted_records,expected_chain,chain_sha256,status)
            values (?,?,?,?,?,?,'{}'::jsonb,0,0,?,?,'IMPORTING')
            """, source, TENANT, f.company(), SOURCE_ORG, POSTING_DATE, "f".repeat(64), "d".repeat(64), "d".repeat(64));
        for (int i = 0; i < 2; i++) {
            jdbc.update("""
                insert into warehouse_account_entries
                (id,tenant_id,company_id,import_id,source_seq,source_key,kind,source_date,
                 account_source_id,account_code,account_name,material_reference_type,material_source_id,
                 warehouse_source_id,material_id,warehouse_id,material_code,material_name,warehouse_code,warehouse_name,
                 source_type,source_material_type,stock_category,uom,uom_name_ru,quantity,amount,
                 additional_dimensions,row_sha256,content)
                values (?,?,?,?,?,?,'BALANCE',?,?,?,'Goods','00000037',?,?,?,?,'FG','Finished product',
                        'WH','Warehouse','FINISHED_GOODS','Product','FINISHED_PRODUCTS',?,'ton',?,?,
                        cast(? as jsonb),?,'{}')
                """, UUID.randomUUID(), TENANT, f.company(), source, i + 1, "fg-source-" + i, POSTING_DATE,
                String.valueOf(i + 1).repeat(32), i == 0 ? "2812" : "2813", SOURCE_MATERIAL, SOURCE_WAREHOUSE,
                material, f.warehouse().getId(), f.unit().getCode(), new BigDecimal(i == 0 ? "7212.3330360" : "-1764"),
                new BigDecimal(i == 0 ? "89139138603.85" : "0"),
                "{\"dimension1_type\":\"08\",\"dimension1_rtref\":\"00000037\",\"dimension1_rrref\":\""
                    + (mismatchedDimensions ? "incorrect" : SOURCE_MATERIAL)
                    + "\",\"dimension2_type\":\"08\",\"dimension2_rtref\":\"00000055\",\"dimension2_rrref\":\""
                    + SOURCE_WAREHOUSE + "\",\"currencySourceId\":null}", String.valueOf(i + 2).repeat(64));
        }
        jdbc.update("update warehouse_account_imports set status='COMPLETED',completed_at=current_timestamp where id=?", source);
    }

    private void insertFinishedGoodsLinks(Fixture f, UUID material) {
        UUID run = UUID.randomUUID();
        jdbc.update("""
            insert into warehouse_history_runs
            (id,tenant_id,company_id,manifest_sha256,organization_source_id,expected_counts,expected_records,
             expected_chain,chain_sha256,status)
            values (?,?,?,?,?,'{}',0,?,?,'IMPORTING')
            """, run, TENANT, f.company(), "a".repeat(64), SOURCE_ORG, "0".repeat(64), "0".repeat(64));
        for (int i = 0; i < 2; i++) {
            UUID record = UUID.randomUUID();
            jdbc.update("""
                insert into warehouse_history_records
                (id,tenant_id,company_id,import_id,source_seq,kind,source_key,source_table,row_sha256,content)
                values (?,?,?,?,?,'SCHEMA',?,'fixture',?,'{}')
                """, record, TENANT, f.company(), run, i + 1, "fg-link-" + run + "-" + i, "c".repeat(64));
            jdbc.update("""
                insert into warehouse_source_master_links
                (tenant_id,company_id,reference_kind,reference_type,source_id,master_id,source_record_id)
                values (?,?,?,?,?,?,?)
                """, TENANT, f.company(), i == 0 ? "MATERIAL" : "WAREHOUSE", i == 0 ? "00000037" : "00000055",
                i == 0 ? SOURCE_MATERIAL : SOURCE_WAREHOUSE, i == 0 ? material : f.warehouse().getId(), record);
        }
        jdbc.update("update warehouse_history_runs set status='COMPLETED' where id=?", run);
    }

    @Test
    void failureAfterSecondValuedPostingRollsBackWholeChunkAndItsReplayKey() {
        Fixture f = fixture();
        UUID run = UUID.randomUUID();
        opening.start(TENANT, f.company(), start(run, 2, "3.0000007", "1.00"));
        ChunkRequest chunk = new ChunkRequest(UUID.randomUUID(), List.of(
            row(f, '1', "1010", "3", "1.00"), row(f, '2', "1040", "0.0000007", "0.00")));
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (calls.incrementAndGet() == 2) throw new BusinessException("Controlled failure after exact cost evidence");
            return result;
        }).when(costing).recordExactOpeningLayer(any(StockMovement.class), any(BigDecimal.class));

        try {
            assertThatThrownBy(() -> opening.postChunk(TENANT, f.company(), run, chunk))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Controlled failure");
        } finally { reset(costing); }
        assertThat(calls.get()).isEqualTo(2);
        assertCompanyStockCounts(f.company(), 0);
        assertThat(count("select count(*) from exact_opening_rows where import_id=?", run)).isZero();
        assertThat(count("select count(*) from audit_logs where tenant_id=? and company_id=? and entity_type='StockMovement'",
            TENANT, f.company())).isZero();
        assertThat(count("select count(*) from idempotency_records where tenant_id=? and company_id=? and operation='EXACT_OPENING_CHUNK'",
            TENANT, f.company())).isZero();
        var rolledBack = opening.status(TENANT, f.company(), run);
        assertThat(rolledBack.acceptedRows()).isZero();
        assertDecimal(rolledBack.acceptedQuantityChecksum(), "0");
        assertDecimal(rolledBack.acceptedValueUzs(), "0");

        assertThat(opening.postChunk(TENANT, f.company(), run, chunk).rows()).hasSize(2);
        assertThat(opening.complete(TENANT, f.company(), run).status()).isEqualTo("COMPLETED");
        assertCompanyStockCounts(f.company(), 2);
        assertThat(count("select count(*) from audit_logs where tenant_id=? and company_id=? and entity_type='StockMovement' and action='POST'",
            TENANT, f.company())).isEqualTo(2);
    }

    @Test
    void completionRequiresDeclaredTotalsAndEachPhysicalRowToReconcile() {
        Fixture f = fixture();
        UUID run = UUID.randomUUID();
        opening.start(TENANT, f.company(), start(run, 2, "3.0000007", "1.00"));
        var first = opening.postChunk(TENANT, f.company(), run,
            new ChunkRequest(UUID.randomUUID(), List.of(row(f, '1', "1010", "3", "1.00"))));
        assertThatThrownBy(() -> opening.complete(TENANT, f.company(), run))
            .isInstanceOf(BusinessException.class).hasMessageContaining("incomplete");
        assertThat(opening.status(TENANT, f.company(), run).status()).isEqualTo("IMPORTING");
        opening.postChunk(TENANT, f.company(), run,
            new ChunkRequest(UUID.randomUUID(), List.of(row(f, '2', "1040", "0.0000007", "0.00"))));

        UUID batch = first.rows().getFirst().stockBatchId();
        // A test-only corruption keeps the aggregate within the declared total yet breaks a source row.
        jdbc.update("update inventory_cost_layers set remaining_quantity=remaining_quantity-0.0000001 where stock_batch_id=?", batch);
        assertThatThrownBy(() -> opening.complete(TENANT, f.company(), run))
            .isInstanceOf(BusinessException.class).hasMessageContaining("does not reconcile");
        assertThat(opening.status(TENANT, f.company(), run).status()).isEqualTo("IMPORTING");
        jdbc.update("update inventory_cost_layers set remaining_quantity=received_quantity where stock_batch_id=?", batch);
        assertThat(opening.complete(TENANT, f.company(), run).status()).isEqualTo("COMPLETED");
    }

    @Test
    void completionRejectsStaleMasterDataMissingSnapshotsAndAllocationLineageIsImmutable() {
        Fixture f = fixture();
        UUID run = UUID.randomUUID();
        opening.start(TENANT, f.company(), start(run, 1, "1", "1.00"));
        var posted = opening.postChunk(TENANT, f.company(), run,
            new ChunkRequest(UUID.randomUUID(), List.of(row(f, '1', "1010", "1", "1.00"))));

        jdbc.update("update warehouses set is_delete=true where id=?", f.warehouse().getId());
        assertCompletionBlocked(f, run);
        jdbc.update("update warehouses set is_delete=false where id=?", f.warehouse().getId());
        jdbc.update("update warehouses set status=null where id=?", f.warehouse().getId());
        assertCompletionBlocked(f, run);
        jdbc.update("update warehouses set status='ACTIVE' where id=?", f.warehouse().getId());
        jdbc.update("update materials set description=null where id=?", f.material().getId());
        assertCompletionBlocked(f, run);
        jdbc.update("update materials set description=? where id=?", marker("Catalog54", SOURCE_MATERIAL), f.material().getId());
        jdbc.update("update stock_movements set uom_id_snapshot=null where id=?", posted.rows().getFirst().movementId());
        assertCompletionBlocked(f, run);
        jdbc.update("update stock_movements set uom_id_snapshot=?,uom_code_snapshot=null where id=?",
            f.unit().getId(), posted.rows().getFirst().movementId());
        assertCompletionBlocked(f, run);
        jdbc.update("update stock_movements set uom_code_snapshot=? where id=?", f.unit().getCode(), posted.rows().getFirst().movementId());
        jdbc.update("update inventory_cost_layers set status='MISSING_COST' where source_movement_id=?", posted.rows().getFirst().movementId());
        assertCompletionBlocked(f, run);
        jdbc.update("update inventory_cost_layers set status='ACTIVE' where source_movement_id=?", posted.rows().getFirst().movementId());
        jdbc.update("update companies set description=null where id=?", f.company());
        assertThatThrownBy(() -> opening.complete(TENANT, f.company(), run))
            .isInstanceOf(BusinessException.class).hasMessageContaining("company source identity changed");
        assertThat(opening.status(TENANT, f.company(), run).status()).isEqualTo("IMPORTING");
        jdbc.update("update companies set description=? where id=?", marker("Catalog61", SOURCE_ORG), f.company());
        assertThatThrownBy(() -> jdbc.update("""
            update stock_movement_allocation_cost_layers set value_delta=0.50
            where allocation_id in (select id from stock_movement_allocations where movement_id=?)
            """, posted.rows().getFirst().movementId()))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThat(opening.complete(TENANT, f.company(), run).status()).isEqualTo("COMPLETED");
    }

    @Test
    void regularStockIsFrozenDuringImportAndExactReferenceCannotBeForged() {
        Fixture f = fixture();
        UUID run = UUID.randomUUID();
        opening.start(TENANT, f.company(), start(run, 1, "0.0000007", "0.00"));
        assertThatThrownBy(() -> stock.createAndPost(ordinary(f, MovementType.RECEIPT, "1", "2")))
            .isInstanceOf(BusinessException.class).hasMessageContaining("import");
        StockMovementCreateRequest draft = new StockMovementCreateRequest();
        draft.setMaterialId(f.material().getId()); draft.setWarehouseId(f.warehouse().getId());
        draft.setStorageLocationId(f.location().getId()); draft.setMovementType(MovementType.RECEIPT);
        draft.setQuantity(BigDecimal.ONE); draft.setUnitCost(new BigDecimal("2"));
        draft.setMovementDate(POSTING_DATE); draft.setReferenceType("IntegrationFixture");
        assertThatThrownBy(() -> stock.create(draft))
            .isInstanceOf(BusinessException.class).hasMessageContaining("import");
        assertCompanyStockCounts(f.company(), 0);
        StockMovement forged = ordinary(f, MovementType.RECEIPT, "1", "2");
        forged.setReferenceType("OPENING_STOCK_EXACT"); forged.setReferenceId(run);
        assertThatThrownBy(() -> stock.createAndPost(forged))
            .isInstanceOf(BusinessException.class).hasMessageContaining("managed");
        var posted = opening.postChunk(TENANT, f.company(), run,
            new ChunkRequest(UUID.randomUUID(), List.of(row(f, '1', "1010", "0.0000007", "0.00"))));
        UUID openingLedger = jdbc.queryForObject("select id from stock_cost_movements where stock_movement_id=?",
            UUID.class, posted.rows().getFirst().movementId());
        assertThatThrownBy(() -> ledger.reverse(openingLedger, "Must not append during an opening import"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("import");
        assertCompanyStockCounts(f.company(), 1);
        opening.complete(TENANT, f.company(), run);

        StockMovement issue = ordinary(f, MovementType.ISSUE, "0.0000003", "0");
        issue.setStockBatchId(posted.rows().getFirst().stockBatchId());
        assertDecimal(stock.createAndPost(issue).getTotalCost(), "0");
        assertDecimal(companySum("stock_balances", "quantity", f.company()), "0.0000004");
        assertDecimal(companySum("inventory_cost_layers", "remaining_quantity", f.company()), "0.0000004");
        StockMovement finalIssue = ordinary(f, MovementType.ISSUE, "0.0000004", "0");
        finalIssue.setStockBatchId(posted.rows().getFirst().stockBatchId());
        assertDecimal(stock.createAndPost(finalIssue).getTotalCost(), "0");
        assertDecimal(companySum("stock_balances", "quantity", f.company()), "0");
        assertDecimal(companySum("inventory_cost_layers", "remaining_quantity", f.company()), "0");
        assertThat(reconciliation.blockerIssues(f.warehouse().getId())).isEmpty();
    }

    @Test
    void weightedIssuesAcrossSourceAccountsConservePhysicalAndLedgerValueUntilFinalDepletion() {
        Fixture f = fixture();
        UUID run = UUID.randomUUID();
        opening.start(TENANT, f.company(), start(run, 2, "4", "101.00"));
        opening.postChunk(TENANT, f.company(), run, new ChunkRequest(UUID.randomUUID(), List.of(
            row(f, '1', "1010", "3", "1.00"), row(f, '2', "1040", "1", "100.00"))));
        opening.complete(TENANT, f.company(), run);
        for (String[] step : List.of(new String[]{"2", "50.50", "2", "50.50"},
                new String[]{"1", "25.25", "1", "25.25"}, new String[]{"1", "25.25", "0", "0"})) {
            var issued = stock.createAndPost(ordinary(f, MovementType.ISSUE, step[0], "0"));
            assertDecimal(issued.getTotalCost(), step[1]);
            assertDecimal(companySum("stock_balances", "quantity", f.company()), step[2]);
            assertDecimal(companySum("stock_batches", "quantity", f.company()), step[2]);
            assertDecimal(companySum("inventory_cost_layers", "remaining_quantity", f.company()), step[2]);
            assertDecimal(companySum("stock_balances", "total_value", f.company()), step[3]);
            assertDecimal(companySum("inventory_cost_layers", "total_cost", f.company()), step[3]);
            assertThat(reconciliation.blockerIssues(f.warehouse().getId())).isEmpty();
        }
        assertDecimal(netOpeningIssueLedgerValue(f.company()), "0");
        assertThat(count("select count(*) from inventory_cost_layers where company_id=? and (remaining_quantity<>0 or total_cost<>0)",
            f.company())).isZero();
    }

    @Test
    void roundedUnitSnapshotDoesNotStrandFinalOpeningBookValue() {
        Fixture f = fixture();
        UUID run = UUID.randomUUID();
        opening.start(TENANT, f.company(), start(run, 1, "3", "1.00"));
        opening.postChunk(TENANT, f.company(), run,
            new ChunkRequest(UUID.randomUUID(), List.of(row(f, '1', "1010", "3", "1.00"))));
        opening.complete(TENANT, f.company(), run);
        for (String[] step : List.of(new String[]{"0.3333", "2", "0.6667"},
                new String[]{"0.3334", "1", "0.3333"}, new String[]{"0.3333", "0", "0"})) {
            assertDecimal(stock.createAndPost(ordinary(f, MovementType.ISSUE, "1", "0")).getTotalCost(), step[0]);
            assertDecimal(companySum("stock_balances", "quantity", f.company()), step[1]);
            assertDecimal(companySum("stock_balances", "total_value", f.company()), step[2]);
            assertDecimal(companySum("inventory_cost_layers", "remaining_quantity", f.company()), step[1]);
            assertDecimal(companySum("inventory_cost_layers", "total_cost", f.company()), step[2]);
            assertThat(reconciliation.blockerIssues(f.warehouse().getId())).isEmpty();
        }
        assertDecimal(netOpeningIssueLedgerValue(f.company()), "0");
    }

    @Test
    void adminCannotCrossCurrentCompanyOrReferenceAnotherCompanyCatalog() {
        Fixture a = fixture();
        Fixture b = fixture();
        CompanyContext.setCompany(a.company());
        UUID run = UUID.randomUUID();
        assertThatThrownBy(() -> opening.start(TENANT, b.company(), start(run, 1, "1", "1")))
            .isInstanceOf(BusinessException.class).hasMessageContaining("current company");
        opening.start(TENANT, a.company(), start(run, 1, "1", "1"));
        Row valid = row(a, '1', "1010", "1", "1");
        Row foreignMaterial = new Row(valid.rowId(), valid.accountSourceId(), valid.accountCode(), valid.materialSourceId(),
            valid.warehouseSourceId(), b.material().getId(), valid.warehouseId(), valid.storageLocationId(), null,
            valid.unit(), valid.quantity(), valid.totalValueUzs());
        Row foreignWarehouse = new Row(valid.rowId(), valid.accountSourceId(), valid.accountCode(), valid.materialSourceId(),
            valid.warehouseSourceId(), valid.materialId(), b.warehouse().getId(), b.location().getId(), null,
            valid.unit(), valid.quantity(), valid.totalValueUzs());
        Row foreignLocation = new Row(valid.rowId(), valid.accountSourceId(), valid.accountCode(), valid.materialSourceId(),
            valid.warehouseSourceId(), valid.materialId(), valid.warehouseId(), b.location().getId(), null,
            valid.unit(), valid.quantity(), valid.totalValueUzs());
        assertThatThrownBy(() -> opening.postChunk(TENANT, a.company(), run,
            new ChunkRequest(UUID.randomUUID(), List.of(foreignMaterial))))
            .isInstanceOf(BusinessException.class).hasMessageContaining("materialId not found");
        for (Row row : List.of(foreignWarehouse, foreignLocation)) {
            assertThatThrownBy(() -> opening.postChunk(TENANT, a.company(), run,
                new ChunkRequest(UUID.randomUUID(), List.of(row))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("another tenant or company");
        }
        CompanyContext.setCompany(b.company());
        assertThatThrownBy(() -> opening.status(TENANT, b.company(), run))
            .isInstanceOf(BusinessException.class).hasMessageContaining("not found in this company");
        assertCompanyStockCounts(a.company(), 0); assertCompanyStockCounts(b.company(), 0);
        CompanyContext.setCompany(a.company());
        assertThat(opening.status(TENANT, a.company(), run).acceptedRows()).isZero();
    }

    @Test
    void catalogProvenanceAndDeclaredTotalsAreCheckedBeforePostingAnyRows() {
        Fixture f = fixture();
        UUID run = UUID.randomUUID();
        opening.start(TENANT, f.company(), start(run, 1, "1", "1"));
        Row valid = row(f, '1', "1010", "1", "1");
        String wrongSource = "e".repeat(32);
        Row wrongProvenance = new Row(SOURCE_ORG+":"+valid.accountSourceId()+":"+wrongSource+":"+SOURCE_WAREHOUSE+":",
            valid.accountSourceId(), valid.accountCode(), wrongSource, valid.warehouseSourceId(), valid.materialId(),
            valid.warehouseId(), valid.storageLocationId(), null, valid.unit(), valid.quantity(), valid.totalValueUzs());
        assertThatThrownBy(() -> opening.postChunk(TENANT, f.company(), run,
            new ChunkRequest(UUID.randomUUID(), List.of(wrongProvenance))))
            .isInstanceOf(BusinessException.class).hasMessageContaining("different 1C source identity");
        assertThatThrownBy(() -> opening.postChunk(TENANT, f.company(), run,
            new ChunkRequest(UUID.randomUUID(), List.of(row(f, '1', "1010", "1", "1.01")))))
            .isInstanceOf(BusinessException.class).hasMessageContaining("exceeds");
        assertThatThrownBy(() -> opening.postChunk(TENANT, f.company(), run,
            new ChunkRequest(UUID.randomUUID(), List.of(row(f, '1', "1010", "0.00000001", "1")))))
            .isInstanceOf(BusinessException.class).hasMessageContaining("Invalid exact opening command");
        assertCompanyStockCounts(f.company(), 0);
        assertThat(opening.status(TENANT, f.company(), run).acceptedRows()).isZero();
    }

    private Fixture fixture() {
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Company company = new Company(); company.setCode("EXACT-COMP-"+suffix);
        company.setName("Exact opening company "+suffix); company.setStatus("ACTIVE");
        company.setDescription("1C source organization Catalog61:"+SOURCE_ORG+" "+marker("Catalog61", SOURCE_ORG));
        company = companies.saveAndFlush(company);
        CompanyContext.setCompany(company.getId());
        UnitOfMeasure unit = new UnitOfMeasure(); unit.setCode("EXACT-UOM-"+suffix);
        unit.setName("Exact stock quantity unit "+suffix); unit.setStatus("ACTIVE");
        unit.setMeasurementNameShort("EA-"+suffix); unit.setMeasurementNameLong("Pieces "+suffix);
        unit.setUnitType(MeasurementUnitType.QUANTITY); unit.setStockQuantityScale(7);
        unit = units.saveAndFlush(unit);
        MaterialGroup group = new MaterialGroup(); group.setCode("EXACT-GROUP-"+suffix);
        group.setName("Exact opening group "+suffix); group.setStatus("ACTIVE"); group = groups.saveAndFlush(group);
        Material material = new Material(); material.setCode("EXACT-MAT-"+suffix);
        material.setName("Exact opening material "+suffix); material.setStatus("ACTIVE");
        material.setMaterialType(MaterialType.TMB); material.setMaterialGroupId(group.getId());
        material.setUnitOfMeasureId(unit.getId()); material.setValuationMethod(ValuationMethod.WEIGHTED_AVERAGE);
        material.setDescription(marker("Catalog54", SOURCE_MATERIAL)); material = materials.saveAndFlush(material);
        Warehouse warehouse = new Warehouse(); warehouse.setCode("EXACT-WH-"+suffix);
        warehouse.setName("Exact opening warehouse "+suffix); warehouse.setStatus("ACTIVE");
        warehouse.setDescription(marker("Catalog85", SOURCE_WAREHOUSE)); warehouse = warehouses.saveAndFlush(warehouse);
        StorageLocation location = new StorageLocation(); location.setCode("EXACT-LOC-"+suffix);
        location.setName("Exact opening location "+suffix); location.setStatus("ACTIVE");
        location.setWarehouseId(warehouse.getId()); location.setAllowMixedMaterials(true); location = locations.saveAndFlush(location);
        return new Fixture(company.getId(), material, warehouse, location, unit);
    }

    private StartRequest start(UUID run, int expectedRows, String quantity, String value) {
        return new StartRequest(run, SOURCE_HASH, PREPARED_HASH, SOURCE_ORG,
            "Isolated PostgreSQL integration source evidence", POSTING_DATE, expectedRows, quantity, value);
    }

    private Row row(Fixture f, char accountDigit, String accountCode, String quantity, String value) {
        String account = String.valueOf(accountDigit).repeat(32);
        return new Row(SOURCE_ORG+":"+account+":"+SOURCE_MATERIAL+":"+SOURCE_WAREHOUSE+":", account, accountCode,
            SOURCE_MATERIAL, SOURCE_WAREHOUSE, f.material().getId(), f.warehouse().getId(), f.location().getId(), null,
            f.unit().getCode(), quantity, value);
    }

    private StockMovement ordinary(Fixture f, MovementType type, String quantity, String cost) {
        StockMovement movement = new StockMovement(); movement.setCompanyId(f.company());
        movement.setMaterialId(f.material().getId()); movement.setWarehouseId(f.warehouse().getId());
        movement.setStorageLocationId(f.location().getId()); movement.setMovementType(type);
        movement.setMovementDate(POSTING_DATE); movement.setQuantity(new BigDecimal(quantity));
        movement.setUnitCost(new BigDecimal(cost)); movement.setReferenceType("IntegrationFixture");
        movement.setReferenceId(UUID.randomUUID()); return movement;
    }

    private void assertEvidence(Fixture f, RowResult row) {
        Map<String, Object> evidence = jdbc.queryForMap("""
            select m.quantity mq, m.total_cost mv, m.source_currency_code_snapshot currency,
                b.quantity bq, s.quantity sq, s.available_quantity aq, s.total_value sv,
                l.received_quantity lq, l.remaining_quantity rq, l.total_cost lv, l.authoritative_value authoritative,
                c.quantity cq, c.total_cost cv, a.on_hand_delta dq, a.total_value_delta dv
            from stock_movements m join stock_batches b on b.id=m.stock_batch_id
            join stock_balances s on s.stock_batch_id=b.id
            join inventory_cost_layers l on l.source_movement_id=m.id
            join stock_cost_movements c on c.stock_movement_id=m.id and c.cost_layer_id=l.id
            join stock_movement_allocations a on a.movement_id=m.id
            where m.id=? and m.tenant_id=? and m.company_id=?
            """, row.movementId(), TENANT, f.company());
        for (String key : List.of("mq", "bq", "sq", "aq", "lq", "rq", "cq", "dq"))
            assertThat((BigDecimal) evidence.get(key)).as(key).isEqualByComparingTo(row.quantity());
        for (String key : List.of("mv", "sv", "lv", "cv", "dv"))
            assertThat((BigDecimal) evidence.get(key)).as(key).isEqualByComparingTo(row.totalValueUzs());
        assertThat(evidence.get("authoritative")).isEqualTo(true);
        assertThat(evidence.get("currency")).isEqualTo("UZS");
    }

    private void assertCompanyStockCounts(UUID company, int expected) {
        for (String table : STOCK_TABLES)
            assertThat(count("select count(*) from "+table+" where tenant_id=? and company_id=?", TENANT, company))
                .as(table).isEqualTo(expected);
    }

    private void assertCompletionBlocked(Fixture f, UUID run) {
        assertThatThrownBy(() -> opening.complete(TENANT, f.company(), run))
            .isInstanceOf(BusinessException.class).hasMessageContaining("does not reconcile");
        assertThat(opening.status(TENANT, f.company(), run).status()).isEqualTo("IMPORTING");
    }

    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    private BigDecimal companySum(String table, String field, UUID company) {
        return jdbc.queryForObject("select coalesce(sum("+field+"),0) from "+table+" where tenant_id=? and company_id=?",
            BigDecimal.class, TENANT, company);
    }
    private BigDecimal netOpeningIssueLedgerValue(UUID company) {
        return jdbc.queryForObject("""
            select coalesce(sum(case when movement_type='OPENING_BALANCE' then total_cost
                when movement_type='ISSUE' then -total_cost else 0 end),0)
            from stock_cost_movements where tenant_id=? and company_id=?
            """, BigDecimal.class, TENANT, company);
    }
    private void assertDecimal(BigDecimal actual, String expected) {
        assertThat(actual).isEqualByComparingTo(new BigDecimal(expected));
    }
    private long zeroInboundCount(Fixture f) {
        return finance.zeroCostInboundCount(f.warehouse().getId(), POSTING_DATE.minusYears(1), LocalDate.now().plusYears(1));
    }
    private UnitOfMeasureUpdateRequest unitUpdate(Fixture f) {
        UnitOfMeasureUpdateRequest request = new UnitOfMeasureUpdateRequest();
        request.setCode(f.unit().getCode()); request.setName(f.unit().getName());
        request.setMeasurementNameShort(f.unit().getMeasurementNameShort());
        request.setMeasurementNameLong(f.unit().getMeasurementNameLong());
        request.setUnitType(f.unit().getUnitType()); return request;
    }
    private String marker(String catalog, String id) {
        return "[1c-mm-migration:"+PREPARED_HASH+":"+catalog+":"+id+"]";
    }
    private record Fixture(UUID company, Material material, Warehouse warehouse, StorageLocation location, UnitOfMeasure unit) {}
}
