package com.enterprise.mm.warehouse.activation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.warehouse.posting.AccountAwareStockPostingService;
import com.enterprise.mm.warehouse.posting.PostingReceipt;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ActivationApplyIdempotencyTest {
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

    @BeforeEach
    void setupScope() {
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void clearScope() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void applyRejectsMissingOrBlankIdempotencyKey() {
        assertThatThrownBy(() -> new ActivationApplyService.ActivationApplyRequest(null, "1010", null, LocalDate.now()))
            .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> new ActivationApplyService.ActivationApplyRequest("   ", "1010", null, LocalDate.now()))
            .isInstanceOf(BadRequestException.class);
    }

    @Test
    void applyReplayReturnsIdenticalResultWithoutReposting() {
        UUID warehouseId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID existingBatchId = UUID.randomUUID();
        LocalDate postingDate = LocalDate.of(2026, 10, 14);
        String idempotencyKey = "act-key-idempotent-1";

        UUID materialId = UUID.randomUUID();
        var candidate = new ActivationPreviewCandidate(
            "src-key-1", "1010", "Materials", materialId, "M01", "Chemical Reagent",
            warehouseId, "W01", "Main Store", "OWNED", "KG",
            new BigDecimal("100.0000000"), new BigDecimal("50000.0000"),
            BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("100.0000000"), new BigDecimal("50000.0000"),
            SourcePositionClassification.ELIGIBLE_DELTA, "row-hash-1", null
        );

        when(previewService.preview(any())).thenReturn(
            new ActivationPreviewResult(1, 1, 0, 0, 0, 0, 0,
                new BigDecimal("100.0000000"), new BigDecimal("50000.0000"),
                new BigDecimal("100.0000000"), new BigDecimal("50000.0000"),
                List.of(candidate), 0, 100)
        );

        // Pre-compute expected hashes using the same formula
        String previewHash = computeExpectedPreviewHash(List.of(candidate));
        String sourceContentHash = computeExpectedSourceHash(List.of(candidate));

        // Mock JDBC returning existing journal row
        when(jdbc.query(any(String.class), any(RowMapper.class), eq(tenantId), eq(companyId), eq(idempotencyKey)))
            .thenAnswer(invocation -> {
                RowMapper mapper = invocation.getArgument(1);
                var rs = mock(java.sql.ResultSet.class);
                when(rs.getString("idempotency_key")).thenReturn(idempotencyKey);
                when(rs.getString("account_code")).thenReturn("1010");
                when(rs.getObject("warehouse_id", UUID.class)).thenReturn(warehouseId);
                when(rs.getObject("posting_date", LocalDate.class)).thenReturn(postingDate);
                when(rs.getString("preview_hash")).thenReturn(previewHash);
                when(rs.getString("source_content_hash")).thenReturn(sourceContentHash);
                when(rs.getInt("applied_count")).thenReturn(1);
                when(rs.getBigDecimal("total_quantity")).thenReturn(new BigDecimal("100.0000000"));
                when(rs.getBigDecimal("total_amount")).thenReturn(new BigDecimal("50000.0000"));
                when(rs.getString("batch_ids_json")).thenReturn("[\"" + existingBatchId + "\"]");
                when(rs.getObject("plan_id", UUID.class)).thenReturn(planId);
                return List.of(mapper.mapRow(rs, 1));
            });

        var request = new ActivationApplyService.ActivationApplyRequest(idempotencyKey, "1010", warehouseId, null, planId);
        var result = applyService.apply(request);

        assertThat(result.idempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(result.appliedCount()).isEqualTo(1);
        assertThat(result.totalActivatedQuantity()).isEqualByComparingTo("100.0000000");
        assertThat(result.totalActivatedAmount()).isEqualByComparingTo("50000.0000");
        assertThat(result.batchIds()).containsExactly(existingBatchId);

        assertThatThrownBy(() -> applyService.apply(new ActivationApplyService.ActivationApplyRequest(
            idempotencyKey, "1010", warehouseId, postingDate)))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("mismatched plan ID");
        assertThatThrownBy(() -> applyService.apply(new ActivationApplyService.ActivationApplyRequest(
            idempotencyKey, "1010", warehouseId, postingDate, UUID.randomUUID())))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("mismatched plan ID");

        // Verify stock posting service was NOT called again on replay
        verify(stockPostingService, never()).postReceipt(any());
    }

    @Test
    void applyThrowsConflictWhenReusedWithDifferentScopeOrAccount() {
        UUID warehouseId = UUID.randomUUID();
        LocalDate postingDate = LocalDate.now();
        String idempotencyKey = "act-key-conflict-1";

        when(previewService.preview(any())).thenReturn(
            new ActivationPreviewResult(0, 0, 0, 0, 0, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), 0, 100)
        );

        when(jdbc.query(any(String.class), any(RowMapper.class), eq(tenantId), eq(companyId), eq(idempotencyKey)))
            .thenAnswer(invocation -> {
                RowMapper mapper = invocation.getArgument(1);
                var rs = mock(java.sql.ResultSet.class);
                when(rs.getString("idempotency_key")).thenReturn(idempotencyKey);
                when(rs.getString("account_code")).thenReturn("1020"); // previously for 1020
                when(rs.getObject("warehouse_id", UUID.class)).thenReturn(warehouseId);
                when(rs.getObject("posting_date", LocalDate.class)).thenReturn(postingDate);
                when(rs.getString("preview_hash")).thenReturn("hash-prev");
                when(rs.getString("source_content_hash")).thenReturn("hash-src");
                when(rs.getInt("applied_count")).thenReturn(1);
                when(rs.getBigDecimal("total_quantity")).thenReturn(BigDecimal.TEN);
                when(rs.getBigDecimal("total_amount")).thenReturn(BigDecimal.TEN);
                when(rs.getString("batch_ids_json")).thenReturn("[]");
                return List.of(mapper.mapRow(rs, 1));
            });

        // Requesting for account "1010" with key previously used for "1020"
        var request = new ActivationApplyService.ActivationApplyRequest(idempotencyKey, "1010", warehouseId, postingDate);
        assertThatThrownBy(() -> applyService.apply(request))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("mismatched scope");
    }

    @Test
    void applyReplaySucceedsEvenIfCurrentPreviewHashOrDeltasChanged() {
        UUID warehouseId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        LocalDate postingDate = LocalDate.now();
        String idempotencyKey = "act-key-replay-2";

        UUID materialId = UUID.randomUUID();
        var candidate = new ActivationPreviewCandidate(
            "src-key-1", "1010", "Materials", materialId, "M01", "Chemical Reagent",
            warehouseId, "W01", "Main Store", "OWNED", "KG",
            new BigDecimal("100.0000000"), new BigDecimal("50000.0000"),
            BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("100.0000000"), new BigDecimal("50000.0000"),
            SourcePositionClassification.ELIGIBLE_DELTA, "row-hash-1", null
        );

        when(previewService.preview(any())).thenReturn(
            new ActivationPreviewResult(1, 1, 0, 0, 0, 0, 0,
                new BigDecimal("100.0000000"), new BigDecimal("50000.0000"),
                new BigDecimal("100.0000000"), new BigDecimal("50000.0000"),
                List.of(candidate), 0, 100)
        );

        when(jdbc.query(any(String.class), any(RowMapper.class), eq(tenantId), eq(companyId), eq(idempotencyKey)))
            .thenAnswer(invocation -> {
                RowMapper mapper = invocation.getArgument(1);
                var rs = mock(java.sql.ResultSet.class);
                when(rs.getString("idempotency_key")).thenReturn(idempotencyKey);
                when(rs.getString("account_code")).thenReturn("1010");
                when(rs.getObject("warehouse_id", UUID.class)).thenReturn(warehouseId);
                when(rs.getObject("posting_date", LocalDate.class)).thenReturn(postingDate);
                when(rs.getString("preview_hash")).thenReturn("PREVIOUS_HASH");
                when(rs.getObject("plan_id", UUID.class)).thenReturn(planId);
                when(rs.getString("source_content_hash")).thenReturn("hash-src");
                when(rs.getInt("applied_count")).thenReturn(1);
                when(rs.getBigDecimal("total_quantity")).thenReturn(new BigDecimal("100.0000000"));
                when(rs.getBigDecimal("total_amount")).thenReturn(new BigDecimal("50000.0000"));
                when(rs.getString("batch_ids_json")).thenReturn("[]");
                return List.of(mapper.mapRow(rs, 1));
            });

        var request = new ActivationApplyService.ActivationApplyRequest(idempotencyKey, "1010", warehouseId, postingDate, planId);
        var result = applyService.apply(request);

        assertThat(result.idempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(result.appliedCount()).isEqualTo(1);
        assertThat(result.totalActivatedQuantity()).isEqualByComparingTo("100.0000000");
        assertThat(result.totalActivatedAmount()).isEqualByComparingTo("50000.0000");
        verify(stockPostingService, never()).postReceipt(any());
        verify(previewService, never()).preview(any());
    }

    private String computeExpectedPreviewHash(List<ActivationPreviewCandidate> items) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            for (var item : items) {
                String row = (item.sourceKey() != null ? item.sourceKey() : "") + ":"
                    + (item.accountCode() != null ? item.accountCode() : "") + ":"
                    + (item.warehouseId() != null ? item.warehouseId() : "") + ":"
                    + (item.deltaQuantity() != null ? item.deltaQuantity().stripTrailingZeros().toPlainString() : "0") + ":"
                    + (item.deltaAmount() != null ? item.deltaAmount().stripTrailingZeros().toPlainString() : "0") + ";";
                md.update(row.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String computeExpectedSourceHash(List<ActivationPreviewCandidate> items) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            for (var item : items) {
                String row = (item.sourceRowHash() != null ? item.sourceRowHash() : "") + ":"
                    + (item.sourceQuantity() != null ? item.sourceQuantity().stripTrailingZeros().toPlainString() : "0") + ":"
                    + (item.sourceAmount() != null ? item.sourceAmount().stripTrailingZeros().toPlainString() : "0") + ";";
                md.update(row.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
