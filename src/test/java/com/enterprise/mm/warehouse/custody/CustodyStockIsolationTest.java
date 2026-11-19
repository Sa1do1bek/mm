package com.enterprise.mm.warehouse.custody;

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
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.InsufficientStockException;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.service.StockBalanceMutationService;
import com.enterprise.mm.warehouse.posting.AccountAwareStockPostingService;
import com.enterprise.mm.warehouse.posting.PostingCommand;
import com.enterprise.mm.warehouse.posting.PostingEvidenceStore;
import com.enterprise.mm.warehouse.posting.PostingJournalService;
import com.enterprise.mm.warehouse.posting.PostingRule;
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

/**
 * P4 AT01: Generic issue/demand cannot spend third-party custody stock (Schet 002 / non-owned).
 * Attempting to issue company demand when only custody stock exists throws InsufficientStockException.
 * Attempting to mix custody and owned accounts/categories throws BadRequestException.
 */
class CustodyStockIsolationTest {
    private final PostingEvidenceStore store = mock(PostingEvidenceStore.class);
    private final PostingJournalService journalService = new PostingJournalService(store);
    private final StockBalanceRepository stockBalanceRepository = mock(StockBalanceRepository.class);
    private final StockBalanceMutationService mutationService = new StockBalanceMutationService();
    private final OwnerEligibilityService eligibilityService = new OwnerEligibilityService();

    private final AccountAwareStockPostingService service = new AccountAwareStockPostingService(
        journalService,
        stockBalanceRepository,
        mutationService,
        store,
        eligibilityService
    );

    private final UUID companyId = UUID.randomUUID();
    private final String tenantId = "navoiy-azot";
    private final UUID contractorOwnerId = UUID.randomUUID();
    private final UUID materialId = UUID.randomUUID();
    private final UUID warehouseId = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    @DisplayName("P4 AT01: Generic company issue cannot spend third-party custody stock when only account 002 exists")
    void genericIssueCannotSpendThirdPartyCustodyStock() throws Exception {
        // Physical stock on hand has 50 units
        var balance = new StockBalance();
        balance.setTenantId(tenantId);
        balance.setCompanyId(companyId);
        balance.setMaterialId(materialId);
        balance.setWarehouseId(warehouseId);
        balance.setQuantity(new BigDecimal("50.0000000"));
        balance.setAvailableQuantity(new BigDecimal("50.0000000"));
        balance.setTotalValue(new BigDecimal("0.0000")); // Custody non-owned has 0 company asset value

        when(stockBalanceRepository.findBalanceForUpdate(
            eq(tenantId), eq(materialId), eq(warehouseId),
            eq(true), eq(null),
            eq(true), eq(null),
            eq(true), eq(null)
        )).thenReturn(Optional.of(balance));

        // Subledger has 50 units on account 002 for contractorOwnerId (CUSTODY)
        // For Company (OWNED, 1010), available quantity is 0
        when(store.getAccountAndOwnerAvailableQuantity(
            tenantId, companyId, materialId, warehouseId, "1010", companyId, "OWNED"
        )).thenReturn(BigDecimal.ZERO);
        when(store.getAccountAndOwnerAvailableQuantity(
            tenantId, companyId, materialId, warehouseId, "1010", null, "OWNED"
        )).thenReturn(BigDecimal.ZERO);

        var rule1010 = new PostingRule(UUID.randomUUID(), "WAREHOUSE", "ISSUE", "OWNED", 1,
            LocalDate.of(2026, 10, 1), null, true, "1010", "UZS");
        when(store.rules(any(), any(), any())).thenReturn(
            List.of(new PostingEvidenceStore.RuleEvidence(rule1010, "{}"))
        );
        when(store.reserve(any(), any(), any(), any(), any())).thenReturn(true);

        var command = new PostingCommand("WAREHOUSE", "ISSUE", "OWNED", UUID.randomUUID(),
            "cmd-generic-issue-custody-blocked", LocalDate.of(2026, 10, 14), 1L, mapper.readTree("{\"qty\":10}"));

        var issueRequest = new AccountAwareStockPostingService.IssueRequest(
            command, materialId, warehouseId,
            null, null, null, null,
            "OWNED", "AVAILABLE", "1010", "PCS",
            new BigDecimal("10.0000000"), null, "UZS", "PROD-DEMAND-001"
        );

        // Must fail with InsufficientStockException because Company has 0 owned stock
        assertThatThrownBy(() -> service.postIssue(issueRequest))
            .isInstanceOf(InsufficientStockException.class)
            .hasMessageContaining("Insufficient stock");

        verify(stockBalanceRepository, never()).save(any());
    }

    @Test
    @DisplayName("P4 AT01: Reject posting custody stock to balance-sheet account 1010")
    void rejectCustodyStockOnBalanceSheetAccount() throws Exception {
        var command = new PostingCommand("WAREHOUSE", "RECEIPT", "CUSTODY", UUID.randomUUID(),
            "cmd-custody-bad-account", LocalDate.of(2026, 10, 14), 1L, mapper.readTree("{\"qty\":10}"));

        var receiptRequest = new AccountAwareStockPostingService.ReceiptRequest(
            command, materialId, warehouseId,
            null, null, null, contractorOwnerId,
            "CUSTODY", "AVAILABLE", "1010", "PCS",
            new BigDecimal("10.0000000"), BigDecimal.ZERO, "UZS", "CUSTODY-RCV-001"
        );

        assertThatThrownBy(() -> service.postReceipt(receiptRequest))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Custody stock category cannot be on balance-sheet account");
    }

    @Test
    @DisplayName("P4 AT01: Reject posting company-owned stock to off-balance custody account 002")
    void rejectOwnedStockOnOffBalanceCustodyAccount() throws Exception {
        var command = new PostingCommand("WAREHOUSE", "RECEIPT", "OWNED", UUID.randomUUID(),
            "cmd-owned-bad-account", LocalDate.of(2026, 10, 14), 1L, mapper.readTree("{\"qty\":10}"));

        var receiptRequest = new AccountAwareStockPostingService.ReceiptRequest(
            command, materialId, warehouseId,
            null, null, null, companyId,
            "OWNED", "AVAILABLE", "002", "PCS",
            new BigDecimal("10.0000000"), new BigDecimal("1000.0000"), "UZS", "OWNED-RCV-001"
        );

        assertThatThrownBy(() -> service.postReceipt(receiptRequest))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Owned stock category cannot be assigned to off-balance custody account");
    }

    @Test
    @DisplayName("P4 AT01: Reject custody stock with null owner or with company as owner")
    void rejectCustodyStockWithInvalidOwner() throws Exception {
        var command1 = new PostingCommand("WAREHOUSE", "RECEIPT", "CUSTODY", UUID.randomUUID(),
            "cmd-custody-null-owner", LocalDate.of(2026, 10, 14), 1L, mapper.readTree("{\"qty\":10}"));

        var nullOwnerRequest = new AccountAwareStockPostingService.ReceiptRequest(
            command1, materialId, warehouseId,
            null, null, null, null,
            "CUSTODY", "AVAILABLE", "002", "PCS",
            new BigDecimal("10.0000000"), BigDecimal.ZERO, "UZS", "CUSTODY-RCV-002"
        );

        assertThatThrownBy(() -> service.postReceipt(nullOwnerRequest))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("require a non-null third-party owner ID");

        var command2 = new PostingCommand("WAREHOUSE", "RECEIPT", "CUSTODY", UUID.randomUUID(),
            "cmd-custody-company-owner", LocalDate.of(2026, 10, 14), 1L, mapper.readTree("{\"qty\":10}"));

        var companyOwnerRequest = new AccountAwareStockPostingService.ReceiptRequest(
            command2, materialId, warehouseId,
            null, null, null, companyId,
            "CUSTODY", "AVAILABLE", "002", "PCS",
            new BigDecimal("10.0000000"), BigDecimal.ZERO, "UZS", "CUSTODY-RCV-003"
        );

        assertThatThrownBy(() -> service.postReceipt(companyOwnerRequest))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Company cannot be the third-party owner of custody stock");
    }
}
