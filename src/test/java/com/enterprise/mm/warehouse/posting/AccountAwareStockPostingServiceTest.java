package com.enterprise.mm.warehouse.posting;

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
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.error.InsufficientStockException;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.service.StockBalanceMutationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountAwareStockPostingServiceTest {
    private final PostingEvidenceStore store = mock(PostingEvidenceStore.class);
    private final PostingJournalService postingJournalService = new PostingJournalService(store);
    private final StockBalanceRepository stockBalanceRepository = mock(StockBalanceRepository.class);
    private final StockBalanceMutationService stockBalanceMutationService = new StockBalanceMutationService();

    private final AccountAwareStockPostingService service = new AccountAwareStockPostingService(
        postingJournalService,
        stockBalanceRepository,
        stockBalanceMutationService
    );

    private final UUID companyId = UUID.randomUUID();
    private final String tenantId = "test-tenant";
    private final UUID materialId = UUID.randomUUID();
    private final UUID warehouseId = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();

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
    void postIssueDecreasesPhysicalStockAndGeneratesAtomicPostingEffects() throws Exception {
        var balance = new StockBalance();
        balance.setTenantId(tenantId);
        balance.setCompanyId(companyId);
        balance.setMaterialId(materialId);
        balance.setWarehouseId(warehouseId);
        balance.setQuantity(new BigDecimal("10.0000000"));
        balance.setAvailableQuantity(new BigDecimal("10.0000000"));
        balance.setTotalValue(new BigDecimal("50000.0000"));

        when(stockBalanceRepository.findPartitionedBalanceForUpdate(
            eq(tenantId), eq(companyId), eq(materialId), eq(warehouseId),
            eq(null), eq(null), eq(null), eq(null), eq("OWNED")
        )).thenReturn(Optional.of(balance));

        var rule = new PostingRule(UUID.randomUUID(), "WAREHOUSE", "ISSUE", "OWNED", 1,
            LocalDate.of(2026, 10, 1), null, true, "1010", "UZS");
        when(store.rules(any(), any(), any())).thenReturn(
            java.util.List.of(new PostingEvidenceStore.RuleEvidence(rule, "{}"))
        );
        when(store.reserve(any(), any(), any(), any(), any())).thenReturn(true);

        var command = new PostingCommand("WAREHOUSE", "ISSUE", "OWNED", UUID.randomUUID(),
            "cmd-issue-1", LocalDate.of(2026, 10, 14), 1L, mapper.readTree("{\"qty\":3}"));

        var request = new AccountAwareStockPostingService.IssueRequest(
            command, materialId, warehouseId,
            null, null, null, null,
            "OWNED", "AVAILABLE", "1010", "PCS",
            new BigDecimal("3.0000000"), new BigDecimal("5000.0000"), "UZS", "source-ref-1"
        );

        PostingReceipt receipt = service.postIssue(request);

        assertThat(receipt).isNotNull();
        assertThat(balance.getQuantity()).isEqualByComparingTo("7.0000000");
        assertThat(balance.getAvailableQuantity()).isEqualByComparingTo("7.0000000");
        assertThat(balance.getTotalValue()).isEqualByComparingTo("35000.0000");

        verify(stockBalanceRepository).save(balance);

        assertThat(receipt.effects()).hasSize(3);

        var stockEffect = receipt.effects().stream()
            .filter(e -> e.type() == PostingEffectType.STOCK).findFirst().orElseThrow();
        assertThat(stockEffect.quantity()).isEqualByComparingTo("-3.0000000");

        var accountEffect = receipt.effects().stream()
            .filter(e -> e.type() == PostingEffectType.ACCOUNT).findFirst().orElseThrow();
        assertThat(accountEffect.quantity()).isEqualByComparingTo("-3.0000000");
        assertThat(accountEffect.amount()).isEqualByComparingTo("-15000.0000");
        assertThat(accountEffect.accountCode()).isEqualTo("1010");

        var costEffect = receipt.effects().stream()
            .filter(e -> e.type() == PostingEffectType.COST).findFirst().orElseThrow();
        assertThat(costEffect.amount()).isEqualByComparingTo("-15000.0000");
    }

    @Test
    void postIssueThrowsInsufficientStockWhenAvailableQuantityIsTooLow() throws Exception {
        var balance = new StockBalance();
        balance.setTenantId(tenantId);
        balance.setCompanyId(companyId);
        balance.setMaterialId(materialId);
        balance.setWarehouseId(warehouseId);
        balance.setQuantity(new BigDecimal("2.0000000"));
        balance.setAvailableQuantity(new BigDecimal("2.0000000"));
        balance.setTotalValue(new BigDecimal("10000.0000"));

        when(stockBalanceRepository.findPartitionedBalanceForUpdate(
            eq(tenantId), eq(companyId), eq(materialId), eq(warehouseId),
            eq(null), eq(null), eq(null), eq(null), eq("OWNED")))
            .thenReturn(Optional.of(balance));

        var rule = new PostingRule(UUID.randomUUID(), "WAREHOUSE", "ISSUE", "OWNED", 1,
            LocalDate.of(2026, 10, 1), null, true, "1010", "UZS");
        when(store.rules(any(), any(), any())).thenReturn(
            java.util.List.of(new PostingEvidenceStore.RuleEvidence(rule, "{}"))
        );
        when(store.reserve(any(), any(), any(), any(), any())).thenReturn(true);

        var command = new PostingCommand("WAREHOUSE", "ISSUE", "OWNED", UUID.randomUUID(),
            "cmd-issue-2", LocalDate.of(2026, 10, 14), 1L, mapper.readTree("{\"qty\":5}"));

        var request = new AccountAwareStockPostingService.IssueRequest(
            command, materialId, warehouseId,
            null, null, null, null,
            "OWNED", "AVAILABLE", "1010", "PCS",
            new BigDecimal("5.0000000"), new BigDecimal("5000.0000"), "UZS", "source-ref-2"
        );

        assertThatThrownBy(() -> service.postIssue(request))
            .isInstanceOf(InsufficientStockException.class);

        verify(stockBalanceRepository, never()).save(balance);
    }

    @Test
    void postingRejectsCurrencyThatConflictsWithApprovedRule() throws Exception {
        var rule = new PostingRule(UUID.randomUUID(), "WAREHOUSE", "RECEIPT", "OWNED", 1,
            LocalDate.of(2026, 10, 1), null, true, "1010", "USD");
        when(store.rules(any(), any(), any())).thenReturn(
            java.util.List.of(new PostingEvidenceStore.RuleEvidence(rule, "{}")));
        when(store.reserve(any(), any(), any(), any(), any())).thenReturn(true);
        var command = new PostingCommand("WAREHOUSE", "RECEIPT", "OWNED", UUID.randomUUID(),
            "currency-mismatch", LocalDate.of(2026, 10, 14), 1L, mapper.readTree("{\"qty\":1}"));
        var request = new AccountAwareStockPostingService.ReceiptRequest(command, materialId,
            warehouseId, null, null, null, null, "OWNED", "AVAILABLE", "1010", "PCS",
            BigDecimal.ONE, BigDecimal.TEN, "UZS", "source");
        assertThatThrownBy(() -> service.postReceipt(request))
            .isInstanceOf(com.enterprise.mm.error.BadRequestException.class)
            .hasMessageContaining("currency");
    }

    @Test
    void postReceiptIncreasesPhysicalStockAndRecordsAccountCostEvidence() throws Exception {
        var balance = new StockBalance();
        balance.setTenantId(tenantId);
        balance.setCompanyId(companyId);
        balance.setMaterialId(materialId);
        balance.setWarehouseId(warehouseId);
        balance.setQuantity(BigDecimal.ZERO);
        balance.setAvailableQuantity(BigDecimal.ZERO);
        balance.setTotalValue(BigDecimal.ZERO);

        when(stockBalanceRepository.findPartitionedBalanceForUpdate(
            eq(tenantId), eq(companyId), eq(materialId), eq(warehouseId),
            eq(null), eq(null), eq(null), eq(null), eq("OWNED")))
            .thenReturn(Optional.of(balance));

        var rule = new PostingRule(UUID.randomUUID(), "WAREHOUSE", "RECEIPT", "OWNED", 1,
            LocalDate.of(2026, 10, 1), null, true, "1010", "UZS");
        when(store.rules(any(), any(), any())).thenReturn(
            java.util.List.of(new PostingEvidenceStore.RuleEvidence(rule, "{}"))
        );
        when(store.reserve(any(), any(), any(), any(), any())).thenReturn(true);

        var command = new PostingCommand("WAREHOUSE", "RECEIPT", "OWNED", UUID.randomUUID(),
            "cmd-receipt-1", LocalDate.of(2026, 10, 14), 1L, mapper.readTree("{\"qty\":8}"));

        var request = new AccountAwareStockPostingService.ReceiptRequest(
            command, materialId, warehouseId,
            null, null, null, null,
            "OWNED", "AVAILABLE", "1010", "PCS",
            new BigDecimal("8.0000000"), new BigDecimal("1250.0000"), "UZS", "po-ref-1"
        );

        PostingReceipt receipt = service.postReceipt(request);

        assertThat(receipt).isNotNull();
        assertThat(balance.getQuantity()).isEqualByComparingTo("8.0000000");
        assertThat(balance.getAvailableQuantity()).isEqualByComparingTo("8.0000000");
        assertThat(balance.getTotalValue()).isEqualByComparingTo("10000.0000");

        verify(stockBalanceRepository).save(balance);

        assertThat(receipt.effects()).hasSize(3);

        var stockEffect = receipt.effects().stream()
            .filter(e -> e.type() == PostingEffectType.STOCK).findFirst().orElseThrow();
        assertThat(stockEffect.quantity()).isEqualByComparingTo("8.0000000");

        var accountEffect = receipt.effects().stream()
            .filter(e -> e.type() == PostingEffectType.ACCOUNT).findFirst().orElseThrow();
        assertThat(accountEffect.quantity()).isEqualByComparingTo("8.0000000");
        assertThat(accountEffect.amount()).isEqualByComparingTo("10000.0000");
        assertThat(accountEffect.accountCode()).isEqualTo("1010");
    }

    @Test
    void finalIssueConsumesTheExactRemainingValue() throws Exception {
        var balance = new StockBalance();
        balance.setTenantId(tenantId);
        balance.setCompanyId(companyId);
        balance.setMaterialId(materialId);
        balance.setWarehouseId(warehouseId);
        balance.setQuantity(new BigDecimal("1.0000000"));
        balance.setAvailableQuantity(new BigDecimal("1.0000000"));
        balance.setTotalValue(new BigDecimal("0.3334"));
        when(stockBalanceRepository.findPartitionedBalanceForUpdate(
            eq(tenantId), eq(companyId), eq(materialId), eq(warehouseId),
            eq(null), eq(null), eq(null), eq(null), eq("OWNED")))
            .thenReturn(Optional.of(balance));
        var rule = new PostingRule(UUID.randomUUID(), "WAREHOUSE", "ISSUE", "OWNED", 1,
            LocalDate.of(2026, 10, 1), null, true, "1010", "UZS");
        when(store.rules(any(), any(), any())).thenReturn(
            java.util.List.of(new PostingEvidenceStore.RuleEvidence(rule, "{}")));
        when(store.reserve(any(), any(), any(), any(), any())).thenReturn(true);

        var command = new PostingCommand("WAREHOUSE", "ISSUE", "OWNED", UUID.randomUUID(),
            "last-unit", LocalDate.of(2026, 10, 14), 1L, mapper.readTree("{}"));
        var request = new AccountAwareStockPostingService.IssueRequest(
            command, materialId, warehouseId, null, null, null, null,
            "OWNED", "AVAILABLE", "1010", "PCS",
            new BigDecimal("1.0000000"), new BigDecimal("0.3333"), "UZS", "residual");

        PostingReceipt receipt = service.postIssue(request);
        assertThat(balance.getQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balance.getTotalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(receipt.effects().stream()
            .filter(effect -> effect.type() == PostingEffectType.ACCOUNT).findFirst().orElseThrow().amount())
            .isEqualByComparingTo("-0.3334");
    }
}
