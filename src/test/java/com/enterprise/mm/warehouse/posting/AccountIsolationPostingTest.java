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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccountIsolationPostingTest {
    private final PostingEvidenceStore store = mock(PostingEvidenceStore.class);
    private final PostingJournalService postingJournalService = new PostingJournalService(store);
    private final StockBalanceRepository stockBalanceRepository = mock(StockBalanceRepository.class);
    private final StockBalanceMutationService stockBalanceMutationService = new StockBalanceMutationService();

    private final AccountAwareStockPostingService service = new AccountAwareStockPostingService(
        postingJournalService,
        stockBalanceRepository,
        stockBalanceMutationService,
        store
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
    @DisplayName("P1 AT01: Issue from Account 1010 consumes only Account 1010 and eligible physical stock, leaving Account 1020 intact")
    void issueFromAccount1010ConsumesOnlyAccount1010AndEligiblePhysicalStock() throws Exception {
        // Physical stock balance has 15 total units (10 from account 1010 and 5 from account 1020)
        var balance = new StockBalance();
        balance.setTenantId(tenantId);
        balance.setCompanyId(companyId);
        balance.setMaterialId(materialId);
        balance.setWarehouseId(warehouseId);
        balance.setQuantity(new BigDecimal("15.0000000"));
        balance.setAvailableQuantity(new BigDecimal("15.0000000"));
        balance.setTotalValue(new BigDecimal("20000.0000")); // 10 @ 1000 + 5 @ 2000

        when(stockBalanceRepository.findPartitionedBalanceForUpdate(
            eq(tenantId), eq(companyId), eq(materialId), eq(warehouseId),
            eq(null), eq(null), eq(null), eq(null), eq("OWNED")
        )).thenReturn(Optional.of(balance));

        // Account 1010 has 10 units recorded in posting evidence
        when(store.getAccountAndOwnerAvailableQuantityForUnit(tenantId, companyId, materialId, warehouseId,
            "1010", null, "OWNED", "PCS", "UZS"))
            .thenReturn(new BigDecimal("10.0000000"));
        // Account 1020 has 5 units recorded in posting evidence

        var rule1010 = new PostingRule(UUID.randomUUID(), "WAREHOUSE", "ISSUE", "OWNED", 1,
            LocalDate.of(2026, 10, 1), null, true, "1010", "UZS");
        when(store.rules(any(), any(), any())).thenReturn(
            List.of(new PostingEvidenceStore.RuleEvidence(rule1010, "{}"))
        );
        when(store.reserve(any(), any(), any(), any(), any())).thenReturn(true);

        var command = new PostingCommand("WAREHOUSE", "ISSUE", "OWNED", UUID.randomUUID(),
            "cmd-issue-isolated-1", LocalDate.of(2026, 10, 14), 1L, mapper.readTree("{\"qty\":4}"));

        var request = new AccountAwareStockPostingService.IssueRequest(
            command, materialId, warehouseId,
            null, null, null, null,
            "OWNED", "AVAILABLE", "1010", "PCS",
            new BigDecimal("4.0000000"), new BigDecimal("1000.0000"), "UZS", "source-ref-1"
        );

        PostingReceipt receipt = service.postIssue(request);

        assertThat(receipt).isNotNull();
        // Physical stock decreased by 4 (15 -> 11)
        assertThat(balance.getQuantity()).isEqualByComparingTo("11.0000000");
        assertThat(balance.getAvailableQuantity()).isEqualByComparingTo("11.0000000");

        // Effects generated specifically against Account 1010
        var accountEffect = receipt.effects().stream()
            .filter(e -> e.type() == PostingEffectType.ACCOUNT).findFirst().orElseThrow();
        assertThat(accountEffect.accountCode()).isEqualTo("1010");
        assertThat(accountEffect.quantity()).isEqualByComparingTo("-4.0000000");
        assertThat(accountEffect.amount()).isEqualByComparingTo("-4000.0000");

        // Verify account 1020 was never touched
        assertThat(receipt.effects().stream().noneMatch(e -> "1020".equals(e.accountCode()))).isTrue();
    }

    @Test
    @DisplayName("P1 AT02: Issue requesting more than Account 1010 available stock fails with InsufficientStockException even if physical stock is available")
    void issueExceedingAccount1010StockFailsEvenIfTotalPhysicalStockIsHigher() throws Exception {
        // Physical stock balance has 15 total units
        var balance = new StockBalance();
        balance.setTenantId(tenantId);
        balance.setCompanyId(companyId);
        balance.setMaterialId(materialId);
        balance.setWarehouseId(warehouseId);
        balance.setQuantity(new BigDecimal("15.0000000"));
        balance.setAvailableQuantity(new BigDecimal("15.0000000"));
        balance.setTotalValue(new BigDecimal("20000.0000"));

        when(stockBalanceRepository.findPartitionedBalanceForUpdate(
            eq(tenantId), eq(companyId), eq(materialId), eq(warehouseId),
            eq(null), eq(null), eq(null), eq(null), eq("OWNED")
        )).thenReturn(Optional.of(balance));

        // Account 1010 has only 6 units remaining
        when(store.getAccountAndOwnerAvailableQuantityForUnit(tenantId, companyId, materialId, warehouseId,
            "1010", null, "OWNED", "PCS", "UZS"))
            .thenReturn(new BigDecimal("6.0000000"));
        // Account 1020 has 9 units

        var rule1010 = new PostingRule(UUID.randomUUID(), "WAREHOUSE", "ISSUE", "OWNED", 1,
            LocalDate.of(2026, 10, 1), null, true, "1010", "UZS");
        when(store.rules(any(), any(), any())).thenReturn(
            List.of(new PostingEvidenceStore.RuleEvidence(rule1010, "{}"))
        );
        when(store.reserve(any(), any(), any(), any(), any())).thenReturn(true);

        // Caller asks for 8 units from Account 1010 (Total physical 15 >= 8, but Account 1010 only has 6!)
        var command = new PostingCommand("WAREHOUSE", "ISSUE", "OWNED", UUID.randomUUID(),
            "cmd-issue-isolated-2", LocalDate.of(2026, 10, 14), 1L, mapper.readTree("{\"qty\":8}"));

        var request = new AccountAwareStockPostingService.IssueRequest(
            command, materialId, warehouseId,
            null, null, null, null,
            "OWNED", "AVAILABLE", "1010", "PCS",
            new BigDecimal("8.0000000"), new BigDecimal("1000.0000"), "UZS", "source-ref-2"
        );

        // Must fail closed with InsufficientStockException and NOT steal 2 units from Account 1020
        assertThatThrownBy(() -> service.postIssue(request))
            .isInstanceOf(InsufficientStockException.class)
            .hasMessageContaining("account");

        // Physical stock balance must not have been modified or saved
        verify(stockBalanceRepository, never()).save(balance);
    }

    @Test
    void legacyPhysicalStockWithoutAccountEvidenceCannotBeIssuedFromAnAccount() throws Exception {
        var balance = new StockBalance();
        balance.setTenantId(tenantId);
        balance.setCompanyId(companyId);
        balance.setMaterialId(materialId);
        balance.setWarehouseId(warehouseId);
        balance.setQuantity(new BigDecimal("10.0000000"));
        balance.setAvailableQuantity(new BigDecimal("10.0000000"));
        balance.setTotalValue(new BigDecimal("100.0000"));
        when(stockBalanceRepository.findPartitionedBalanceForUpdate(
            eq(tenantId), eq(companyId), eq(materialId), eq(warehouseId),
            eq(null), eq(null), eq(null), eq(null), eq("OWNED")))
            .thenReturn(Optional.of(balance));
        var rule = new PostingRule(UUID.randomUUID(), "WAREHOUSE", "ISSUE", "OWNED", 1,
            LocalDate.of(2026, 10, 1), null, true, "1010", "UZS");
        when(store.rules(any(), any(), any())).thenReturn(
            List.of(new PostingEvidenceStore.RuleEvidence(rule, "{}")));
        when(store.reserve(any(), any(), any(), any(), any())).thenReturn(true);
        var command = new PostingCommand("WAREHOUSE", "ISSUE", "OWNED", UUID.randomUUID(),
            "legacy-without-account", LocalDate.of(2026, 10, 14), 1L, mapper.readTree("{\"qty\":1}"));
        var request = new AccountAwareStockPostingService.IssueRequest(command, materialId,
            warehouseId, null, null, null, null, "OWNED", "AVAILABLE", "1010", "PCS",
            BigDecimal.ONE, BigDecimal.TEN, "UZS", "legacy");

        assertThatThrownBy(() -> service.postIssue(request)).isInstanceOf(InsufficientStockException.class);
        verify(stockBalanceRepository, never()).save(balance);
    }
}
