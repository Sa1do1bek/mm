package com.enterprise.mm.warehouse.activation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.warehouse.posting.AccountAwareStockPostingService;
import com.enterprise.mm.warehouse.posting.PostingReceipt;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ActivationApplyServiceTest {
    private final ActivationPreviewService previewService = mock(ActivationPreviewService.class);
    private final AccountAwareStockPostingService stockPostingService = mock(AccountAwareStockPostingService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private final ActivationApplyService applyService = new ActivationApplyService(
        previewService,
        stockPostingService,
        jdbc
    );

    private final UUID companyId = UUID.randomUUID();
    private final String tenantId = "test-tenant";
    private final UUID importId = UUID.randomUUID();
    private final LocalDate snapshotDate = LocalDate.of(2026, 10, 14);
    private final String manifestSha256 = "a".repeat(64);

    @BeforeEach
    void setupScope() {
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);
        when(jdbc.query(org.mockito.ArgumentMatchers.contains("FROM warehouse_account_imports"),
            any(org.springframework.jdbc.core.RowMapper.class), eq(tenantId), eq(companyId), eq(importId)))
            .thenAnswer(invocation -> {
                org.springframework.jdbc.core.RowMapper<?> rowMapper = invocation.getArgument(1);
                java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                when(rs.getObject("snapshot_date", LocalDate.class)).thenReturn(snapshotDate);
                when(rs.getString("manifest_sha256")).thenReturn(manifestSha256);
                return List.of(rowMapper.mapRow(rs, 0));
            });
        when(jdbc.update(org.mockito.ArgumentMatchers.contains("SET status = 'APPLIED'"), any(), any(), any()))
            .thenReturn(1);
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("FROM warehouse_posting_rules"),
            any(Object[].class)))
            .thenReturn(List.of(java.util.Map.of("account_code", "1010", "currency_code", "UZS")));
    }

    @AfterEach
    void clearScope() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    private void mockApprovedPlan(UUID planId, String accountCode, UUID warehouseId, LocalDate postingDate, String previewHash, String sourceContentHash) {
        when(jdbc.query(org.mockito.ArgumentMatchers.contains("warehouse_activation_plans"), any(org.springframework.jdbc.core.RowMapper.class), any(), any(), any()))
            .thenAnswer(invocation -> {
                org.springframework.jdbc.core.RowMapper<?> rowMapper = invocation.getArgument(1);
                java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                when(rs.getObject("id", UUID.class)).thenReturn(planId);
                when(rs.getString("plan_code")).thenReturn("PLAN-1010-001");
                when(rs.getString("account_code")).thenReturn(accountCode);
                when(rs.getObject("warehouse_id", UUID.class)).thenReturn(warehouseId);
                when(rs.getObject("posting_date", LocalDate.class)).thenReturn(postingDate != null ? postingDate : LocalDate.now());
                when(rs.getString("preview_hash")).thenReturn(previewHash);
                when(rs.getString("source_content_hash")).thenReturn(sourceContentHash);
                when(rs.getObject("source_import_id", UUID.class)).thenReturn(importId);
                when(rs.getObject("source_snapshot_date", LocalDate.class)).thenReturn(snapshotDate);
                when(rs.getString("source_manifest_sha256")).thenReturn(manifestSha256);
                when(rs.getObject("cutover_date", LocalDate.class)).thenReturn(snapshotDate);
                when(rs.getString("status")).thenReturn("APPROVED");
                when(rs.getInt("item_count")).thenReturn(1);
                when(rs.getBigDecimal("total_quantity")).thenReturn(new BigDecimal("100.0000000"));
                when(rs.getBigDecimal("total_amount")).thenReturn(new BigDecimal("10000.0000"));
                when(rs.getString("proposed_by")).thenReturn("accountant-1");
                return List.of(rowMapper.mapRow(rs, 1));
            });
    }

    @Test
    void applyRejectsMissingIdempotencyKey() {
        assertThatThrownBy(() -> new ActivationApplyService.ActivationApplyRequest(null, "1010", null, LocalDate.now()))
            .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> new ActivationApplyService.ActivationApplyRequest("  ", "1010", null, LocalDate.now()))
            .isInstanceOf(BadRequestException.class);
    }

    @Test
    void applyRejectsMissingPlanId() {
        var request = new ActivationApplyService.ActivationApplyRequest("act-key-no-plan", "1010", null, LocalDate.now());
        assertThatThrownBy(() -> applyService.apply(request))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("planId");
    }

    @Test
    void applyReturnsZeroWhenNoEligibleCandidatesExist() {
        when(jdbc.queryForObject(any(), any(Class.class), any(), any())).thenReturn(1);
        when(previewService.preview(any())).thenReturn(
            new ActivationPreviewResult(0, 0, 0, 0, 0, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), 0, 100)
        );

        UUID planId = UUID.randomUUID();
        String pHash = applyService.computePreviewHash(List.of());
        String sHash = applyService.computeSourceContentHash(List.of());
        mockApprovedPlan(planId, "1010", null, LocalDate.now(), pHash, sHash);

        var request = new ActivationApplyService.ActivationApplyRequest("act-key-1", "1010", null, LocalDate.now(), planId);
        var result = applyService.apply(request);

        assertThat(result.appliedCount()).isZero();
        assertThat(result.totalActivatedQuantity()).isZero();
        assertThat(result.totalActivatedAmount()).isZero();
        assertThat(result.batchIds()).isEmpty();
    }

    @Test
    void applyProcessesEligibleDeltaAndReturnsReceipt() {
        when(jdbc.queryForObject(any(), any(Class.class), any(), any(), any(), any(), any())).thenReturn(1);

        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();

        var candidate = new ActivationPreviewCandidate(
            "balance:" + "a".repeat(220), "1010", "Materials", materialId, "M01", "Chemical Reagent",
            warehouseId, "W01", "Main Store", "OWNED", "KG",
            new BigDecimal("100.0000000"), new BigDecimal("10000.0000"),
            BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("100.0000000"), new BigDecimal("10000.0000"),
            SourcePositionClassification.ELIGIBLE_DELTA, "hash-123", null
        );

        when(previewService.preview(any())).thenReturn(
            new ActivationPreviewResult(1, 1, 0, 0, 0, 0, 0,
                new BigDecimal("100.0000000"), new BigDecimal("10000.0000"),
                new BigDecimal("100.0000000"), new BigDecimal("10000.0000"),
                List.of(candidate), 0, 100)
        );

        var effect = new com.enterprise.mm.warehouse.posting.PostingEffect(
            com.enterprise.mm.warehouse.posting.PostingEffectType.STOCK,
            materialId, warehouseId, "1010", "KG", new BigDecimal("100.0000000"), BigDecimal.ZERO, null
        );
        var postingReceipt = new PostingReceipt(batchId, UUID.randomUUID(), 1, LocalDate.now(), List.of(effect));
        when(stockPostingService.postReceipt(any())).thenReturn(postingReceipt);

        UUID planId = UUID.randomUUID();
        String pHash = applyService.computePreviewHash(List.of(candidate));
        String sHash = applyService.computeSourceContentHash(List.of(candidate));
        mockApprovedPlan(planId, "1010", warehouseId, LocalDate.now(), pHash, sHash);

        var request = new ActivationApplyService.ActivationApplyRequest("act-key-2", "1010", warehouseId, LocalDate.now(), planId);
        var result = applyService.apply(request);

        assertThat(result.appliedCount()).isEqualTo(1);
        assertThat(result.totalActivatedQuantity()).isEqualByComparingTo("100.0000000");
        assertThat(result.totalActivatedAmount()).isEqualByComparingTo("10000.0000");
        assertThat(result.batchIds()).containsExactly(batchId);
        var posted = org.mockito.ArgumentCaptor.forClass(AccountAwareStockPostingService.ReceiptRequest.class);
        org.mockito.Mockito.verify(stockPostingService).postReceipt(posted.capture());
        assertThat(posted.getValue().command().idempotencyKey()).startsWith("activation:").hasSize(75);
    }

    @Test
    void applyFailsWhenNoApprovedRuleExistsForAccount() {
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("FROM warehouse_posting_rules"),
            any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForObject(any(), any(Class.class), any(), any(), any(), any(), any())).thenReturn(0);

        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();

        var candidate = new ActivationPreviewCandidate(
            "src-item-1", "014", "Holding Items", materialId, "M01", "Tools",
            warehouseId, "W01", "Main Store", "OWNED", "PCS",
            new BigDecimal("10.0000000"), new BigDecimal("5000.0000"),
            BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("10.0000000"), new BigDecimal("5000.0000"),
            SourcePositionClassification.ELIGIBLE_DELTA, "hash-014", null
        );

        when(previewService.preview(any())).thenReturn(
            new ActivationPreviewResult(1, 1, 0, 0, 0, 0, 0,
                new BigDecimal("10.0000000"), new BigDecimal("5000.0000"),
                new BigDecimal("10.0000000"), new BigDecimal("5000.0000"),
                List.of(candidate), 0, 100)
        );

        UUID planId = UUID.randomUUID();
        String pHash = applyService.computePreviewHash(List.of(candidate));
        String sHash = applyService.computeSourceContentHash(List.of(candidate));
        mockApprovedPlan(planId, "014", warehouseId, LocalDate.now(), pHash, sHash);

        var request = new ActivationApplyService.ActivationApplyRequest("act-key-014", "014", warehouseId, LocalDate.now(), planId);
        assertThatThrownBy(() -> applyService.apply(request))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("No approved opening posting rule exists for account 014");
    }

    @Test
    void applyReplaysExistingJournalWithoutRecomputingPreview() throws Exception {
        UUID warehouseId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        LocalDate postingDate = LocalDate.of(2026, 10, 15);

        when(jdbc.query(any(String.class), any(org.springframework.jdbc.core.RowMapper.class), any(), any(), any()))
            .thenAnswer(invocation -> {
                org.springframework.jdbc.core.RowMapper<?> rowMapper = invocation.getArgument(1);
                java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                when(rs.getString("idempotency_key")).thenReturn("replayed-key");
                when(rs.getString("account_code")).thenReturn("1010");
                when(rs.getObject("warehouse_id", UUID.class)).thenReturn(warehouseId);
                when(rs.getObject("posting_date", LocalDate.class)).thenReturn(postingDate);
                when(rs.getString("preview_hash")).thenReturn("prev-hash");
                when(rs.getString("source_content_hash")).thenReturn("src-hash");
                when(rs.getInt("applied_count")).thenReturn(5);
                when(rs.getBigDecimal("total_quantity")).thenReturn(new BigDecimal("500.0000000"));
                when(rs.getBigDecimal("total_amount")).thenReturn(new BigDecimal("50000.0000"));
                when(rs.getString("batch_ids_json")).thenReturn("[]");
                when(rs.getObject("plan_id", UUID.class)).thenReturn(planId);
                return List.of(rowMapper.mapRow(rs, 1));
            });

        var request = new ActivationApplyService.ActivationApplyRequest("replayed-key", "1010", warehouseId, postingDate, planId);
        var result = applyService.apply(request);

        assertThat(result.idempotencyKey()).isEqualTo("replayed-key");
        assertThat(result.appliedCount()).isEqualTo(5);
        assertThat(result.totalActivatedQuantity()).isEqualByComparingTo("500.0000000");
        assertThat(result.totalActivatedAmount()).isEqualByComparingTo("50000.0000");
        // Verify preview was never computed on replay
        org.mockito.Mockito.verifyNoInteractions(previewService);
    }

    @Test
    void applyRejectsIdempotencyKeyReuseWithMismatchedScope() throws Exception {
        UUID warehouseId = UUID.randomUUID();
        LocalDate postingDate = LocalDate.of(2026, 10, 15);

        when(jdbc.query(any(String.class), any(org.springframework.jdbc.core.RowMapper.class), any(), any(), any()))
            .thenAnswer(invocation -> {
                org.springframework.jdbc.core.RowMapper<?> rowMapper = invocation.getArgument(1);
                java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                when(rs.getString("idempotency_key")).thenReturn("reused-key");
                when(rs.getString("account_code")).thenReturn("1010");
                when(rs.getObject("warehouse_id", UUID.class)).thenReturn(warehouseId);
                when(rs.getObject("posting_date", LocalDate.class)).thenReturn(postingDate);
                when(rs.getString("preview_hash")).thenReturn("prev-hash");
                when(rs.getString("source_content_hash")).thenReturn("src-hash");
                when(rs.getInt("applied_count")).thenReturn(5);
                when(rs.getBigDecimal("total_quantity")).thenReturn(new BigDecimal("500.0000000"));
                when(rs.getBigDecimal("total_amount")).thenReturn(new BigDecimal("50000.0000"));
                when(rs.getString("batch_ids_json")).thenReturn("[]");
                return List.of(rowMapper.mapRow(rs, 1));
            });

        // Request with different account code (014 instead of 1010)
        var request = new ActivationApplyService.ActivationApplyRequest("reused-key", "014", warehouseId, postingDate);
        assertThatThrownBy(() -> applyService.apply(request))
            .isInstanceOf(com.enterprise.mm.error.ConflictException.class)
            .hasMessageContaining("mismatched scope");
    }

    @Test
    void applyLoadsAllCandidatesAcrossPagesWithoutTruncation() {
        when(jdbc.queryForObject(any(), any(Class.class), any(), any(), any(), any(), any())).thenReturn(1);

        UUID warehouseId = UUID.randomUUID();
        UUID batchId1 = UUID.randomUUID();
        UUID batchId2 = UUID.randomUUID();

        var candidate1 = new ActivationPreviewCandidate(
            "src-item-1", "1010", "Materials", UUID.randomUUID(), "M01", "Chemical Reagent",
            warehouseId, "W01", "Main Store", "OWNED", "KG",
            new BigDecimal("50.0000000"), new BigDecimal("5000.0000"),
            BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("50.0000000"), new BigDecimal("5000.0000"),
            SourcePositionClassification.ELIGIBLE_DELTA, "hash-1", null
        );
        var candidate2 = new ActivationPreviewCandidate(
            "src-item-2", "1010", "Materials", UUID.randomUUID(), "M02", "Catalyst",
            warehouseId, "W01", "Main Store", "OWNED", "KG",
            new BigDecimal("50.0000000"), new BigDecimal("5000.0000"),
            BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("50.0000000"), new BigDecimal("5000.0000"),
            SourcePositionClassification.ELIGIBLE_DELTA, "hash-2", null
        );

        // Page 0 returns candidate1 with totalElements = 2
        when(previewService.preview(org.mockito.ArgumentMatchers.argThat(q -> q != null && q.page() == 0)))
            .thenReturn(new ActivationPreviewResult(2, 2, 0, 0, 0, 0, 0,
                new BigDecimal("100.0000000"), new BigDecimal("10000.0000"),
                new BigDecimal("100.0000000"), new BigDecimal("10000.0000"),
                List.of(candidate1), 0, 1));

        // Page 1 returns candidate2 with totalElements = 2
        when(previewService.preview(org.mockito.ArgumentMatchers.argThat(q -> q != null && q.page() == 1)))
            .thenReturn(new ActivationPreviewResult(2, 2, 0, 0, 0, 0, 0,
                new BigDecimal("100.0000000"), new BigDecimal("10000.0000"),
                new BigDecimal("100.0000000"), new BigDecimal("10000.0000"),
                List.of(candidate2), 1, 1));

        var effect1 = new com.enterprise.mm.warehouse.posting.PostingEffect(
            com.enterprise.mm.warehouse.posting.PostingEffectType.STOCK,
            candidate1.materialId(), warehouseId, "1010", "KG", new BigDecimal("50.0000000"), BigDecimal.ZERO, null
        );
        var receipt1 = new PostingReceipt(batchId1, UUID.randomUUID(), 1, LocalDate.now(), List.of(effect1));

        var effect2 = new com.enterprise.mm.warehouse.posting.PostingEffect(
            com.enterprise.mm.warehouse.posting.PostingEffectType.STOCK,
            candidate2.materialId(), warehouseId, "1010", "KG", new BigDecimal("50.0000000"), BigDecimal.ZERO, null
        );
        var receipt2 = new PostingReceipt(batchId2, UUID.randomUUID(), 1, LocalDate.now(), List.of(effect2));

        UUID planId = UUID.randomUUID();
        String pHash = applyService.computePreviewHash(List.of(candidate1, candidate2));
        String sHash = applyService.computeSourceContentHash(List.of(candidate1, candidate2));
        mockApprovedPlan(planId, "1010", warehouseId, LocalDate.now(), pHash, sHash);
        when(stockPostingService.postReceipt(any())).thenReturn(receipt1, receipt2);

        var request = new ActivationApplyService.ActivationApplyRequest("act-all-pages", "1010", warehouseId, LocalDate.now(), planId);
        var result = applyService.apply(request);

        assertThat(result.appliedCount()).isEqualTo(2);
        assertThat(result.totalActivatedQuantity()).isEqualByComparingTo("100.0000000");
        assertThat(result.totalActivatedAmount()).isEqualByComparingTo("10000.0000");
        assertThat(result.batchIds()).containsExactly(batchId1, batchId2);
    }

    @Test
    void applyRejectsPlanWhenNotApproved() {
        UUID planId = UUID.randomUUID();
        when(jdbc.query(org.mockito.ArgumentMatchers.contains("warehouse_activation_plans"), any(org.springframework.jdbc.core.RowMapper.class), any(), any(), any()))
            .thenAnswer(invocation -> {
                org.springframework.jdbc.core.RowMapper<?> rowMapper = invocation.getArgument(1);
                java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                when(rs.getObject("id", UUID.class)).thenReturn(planId);
                when(rs.getString("plan_code")).thenReturn("PLAN-1010-001");
                when(rs.getString("account_code")).thenReturn("1010");
                when(rs.getObject("warehouse_id", UUID.class)).thenReturn(null);
                when(rs.getObject("posting_date", LocalDate.class)).thenReturn(LocalDate.now());
                when(rs.getString("preview_hash")).thenReturn("hash-123");
                when(rs.getString("source_content_hash")).thenReturn("src-hash-123");
                when(rs.getString("status")).thenReturn("PROPOSED"); // Not APPROVED
                when(rs.getInt("item_count")).thenReturn(1);
                when(rs.getBigDecimal("total_quantity")).thenReturn(new BigDecimal("10.0000000"));
                when(rs.getBigDecimal("total_amount")).thenReturn(new BigDecimal("100.0000"));
                return List.of(rowMapper.mapRow(rs, 1));
            });

        var request = new ActivationApplyService.ActivationApplyRequest("act-unapproved-plan", "1010", null, LocalDate.now(), planId);
        assertThatThrownBy(() -> applyService.apply(request))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("must be in APPROVED status");
    }

    @Test
    void approvePlanRejectsSelfApprovalByProposer() {
        UUID planId = UUID.randomUUID();
        try {
            org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(new org.springframework.security.authentication.TestingAuthenticationToken("accountant-proposer", null, "ROLE_ACCOUNTANT"));

            when(jdbc.query(org.mockito.ArgumentMatchers.contains("warehouse_activation_plans"), any(org.springframework.jdbc.core.RowMapper.class), any(), any(), any()))
                .thenAnswer(invocation -> {
                    org.springframework.jdbc.core.RowMapper<?> rowMapper = invocation.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getObject("id", UUID.class)).thenReturn(planId);
                    when(rs.getString("plan_code")).thenReturn("PLAN-1010-001");
                    when(rs.getString("account_code")).thenReturn("1010");
                    when(rs.getObject("warehouse_id", UUID.class)).thenReturn(UUID.randomUUID());
                    when(rs.getObject("posting_date", LocalDate.class)).thenReturn(LocalDate.now());
                    when(rs.getString("preview_hash")).thenReturn("hash-123");
                    when(rs.getString("source_content_hash")).thenReturn("src-hash-123");
                    when(rs.getString("status")).thenReturn("PROPOSED");
                    when(rs.getInt("item_count")).thenReturn(1);
                    when(rs.getBigDecimal("total_quantity")).thenReturn(new BigDecimal("10.0000000"));
                    when(rs.getBigDecimal("total_amount")).thenReturn(new BigDecimal("100.0000"));
                    when(rs.getString("proposed_by")).thenReturn("accountant-proposer");
                    return List.of(rowMapper.mapRow(rs, 1));
                });

            assertThatThrownBy(() -> applyService.approvePlan(planId, "notes"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Segregation of duties violation");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void applyRejectsStalePlanWhenPreviewHashMismatched() {
        UUID planId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        when(jdbc.queryForObject(any(), any(Class.class), any(), any(), any(), any(), any())).thenReturn(1);

        // Plan mock with APPROVED status but preview_hash "old-hash"
        when(jdbc.query(org.mockito.ArgumentMatchers.contains("warehouse_activation_plans"), any(org.springframework.jdbc.core.RowMapper.class), any(), any(), any()))
            .thenAnswer(invocation -> {
                org.springframework.jdbc.core.RowMapper<?> rowMapper = invocation.getArgument(1);
                java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                when(rs.getObject("id", UUID.class)).thenReturn(planId);
                when(rs.getString("plan_code")).thenReturn("PLAN-1010-001");
                when(rs.getString("account_code")).thenReturn("1010");
                when(rs.getObject("warehouse_id", UUID.class)).thenReturn(warehouseId);
                when(rs.getObject("posting_date", LocalDate.class)).thenReturn(LocalDate.now());
                when(rs.getString("preview_hash")).thenReturn("old-hash");
                when(rs.getString("source_content_hash")).thenReturn("src-hash");
                when(rs.getString("status")).thenReturn("APPROVED");
                when(rs.getObject("source_import_id", UUID.class)).thenReturn(importId);
                when(rs.getObject("source_snapshot_date", LocalDate.class)).thenReturn(snapshotDate);
                when(rs.getString("source_manifest_sha256")).thenReturn(manifestSha256);
                when(rs.getObject("cutover_date", LocalDate.class)).thenReturn(snapshotDate);
                when(rs.getInt("item_count")).thenReturn(1);
                when(rs.getBigDecimal("total_quantity")).thenReturn(new BigDecimal("10.0000000"));
                when(rs.getBigDecimal("total_amount")).thenReturn(new BigDecimal("100.0000"));
                return List.of(rowMapper.mapRow(rs, 1));
            });

        var candidate = new ActivationPreviewCandidate(
            "src-item-1", "1010", "Materials", UUID.randomUUID(), "M01", "Chemical Reagent",
            warehouseId, "W01", "Main Store", "OWNED", "KG",
            new BigDecimal("10.0000000"), new BigDecimal("100.0000"),
            BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("10.0000000"), new BigDecimal("100.0000"),
            SourcePositionClassification.ELIGIBLE_DELTA, "hash-drifted", null
        );

        when(previewService.preview(any())).thenReturn(
            new ActivationPreviewResult(1, 1, 0, 0, 0, 0, 0,
                new BigDecimal("10.0000000"), new BigDecimal("100.0000"),
                new BigDecimal("10.0000000"), new BigDecimal("100.0000"),
                List.of(candidate), 0, 100)
        );

        var request = new ActivationApplyService.ActivationApplyRequest("act-stale-plan", "1010", warehouseId, LocalDate.now(), planId);
        assertThatThrownBy(() -> applyService.apply(request))
            .isInstanceOf(com.enterprise.mm.error.ConflictException.class)
            .hasMessageContaining("Stale activation plan");
    }
}
