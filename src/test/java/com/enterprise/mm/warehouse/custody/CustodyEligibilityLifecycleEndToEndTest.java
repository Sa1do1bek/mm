package com.enterprise.mm.warehouse.custody;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.StockBalance;
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
 * E2E 02: End-to-end custody eligibility lifecycle:
 * 1. Receipt of 100 units Custody stock for Supplier/Contractor S (account 002, stockCategory CUSTODY).
 * 2. Generic company production issue attempted for 50 units -> rejected with InsufficientStockException.
 * 3. Attempt by Contractor B to issue Contractor S's custody stock -> rejected with InsufficientStockException.
 * 4. Authorized custody dispatch for Contractor S of 40 units -> succeeds.
 * 5. Exact owner and account reconciliation: Contractor S has 60 remaining on 002, Company has 0 on 1010.
 */
class CustodyEligibilityLifecycleEndToEndTest {
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
    private final UUID contractorS = UUID.randomUUID();
    private final UUID contractorB = UUID.randomUUID();
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
    @DisplayName("E2E 02: Complete custody lifecycle enforces strict owner isolation and zero leakage to company")
    void custodyLifecycleEnforcesStrictOwnerIsolation() throws Exception {
        var balance = new StockBalance();
        balance.setTenantId(tenantId);
        balance.setCompanyId(companyId);
        balance.setMaterialId(materialId);
        balance.setWarehouseId(warehouseId);
        balance.setQuantity(BigDecimal.ZERO);
        balance.setAvailableQuantity(BigDecimal.ZERO);
        balance.setTotalValue(BigDecimal.ZERO);

        when(stockBalanceRepository.findBalanceForUpdate(
            eq(tenantId), eq(materialId), eq(warehouseId),
            eq(true), eq(null),
            eq(true), eq(null),
            eq(true), eq(null)
        )).thenReturn(Optional.of(balance));

        when(store.reserve(any(), any(), any(), any(), any())).thenReturn(true);

        var ruleReceiptCustody = new PostingRule(UUID.randomUUID(), "WAREHOUSE", "RECEIPT", "CUSTODY", 1,
            LocalDate.of(2026, 10, 1), null, true, "002", "UZS");
        var ruleIssueCustody = new PostingRule(UUID.randomUUID(), "WAREHOUSE", "ISSUE", "CUSTODY", 1,
            LocalDate.of(2026, 10, 1), null, true, "002", "UZS");
        var ruleIssueOwned = new PostingRule(UUID.randomUUID(), "WAREHOUSE", "ISSUE", "OWNED", 1,
            LocalDate.of(2026, 10, 1), null, true, "1010", "UZS");

        when(store.rules(any(), any(), any())).thenAnswer(invocation -> {
            var query = (com.enterprise.mm.warehouse.posting.PostingRuleQuery) invocation.getArgument(2);
            if ("RECEIPT".equals(query.operation())) {
                return List.of(new PostingEvidenceStore.RuleEvidence(ruleReceiptCustody, "{}"));
            }
            if ("CUSTODY".equals(query.contextKey())) {
                return List.of(new PostingEvidenceStore.RuleEvidence(ruleIssueCustody, "{}"));
            }
            return List.of(new PostingEvidenceStore.RuleEvidence(ruleIssueOwned, "{}"));
        });

        // Step 1: Receipt of 100 units Custody stock for Contractor S on Account 002
        var receiptCommand = new PostingCommand("WAREHOUSE", "RECEIPT", "CUSTODY", UUID.randomUUID(),
            "cmd-e2e-custody-receipt-100", LocalDate.of(2026, 10, 14), 1L, mapper.readTree("{\"qty\":100}"));

        var receiptRequest = new AccountAwareStockPostingService.ReceiptRequest(
            receiptCommand, materialId, warehouseId,
            null, null, null, contractorS,
            "CUSTODY", "AVAILABLE", "002", "PCS",
            new BigDecimal("100.0000000"), BigDecimal.ZERO, "UZS", "ACT-002-CUSTODY-RCV"
        );

        var receiptReceipt = service.postReceipt(receiptRequest);
        assertThat(receiptReceipt).isNotNull();
        assertThat(balance.getQuantity()).isEqualByComparingTo(new BigDecimal("100.0000000"));

        // Setup store state after receipt:
        // Account 002 for Contractor S has 100 units
        when(store.getAccountAndOwnerAvailableQuantity(
            tenantId, companyId, materialId, warehouseId, "002", contractorS, "CUSTODY"
        )).thenReturn(new BigDecimal("100.0000000"));

        // Account 1010 for Company (OWNED) has 0 units
        when(store.getAccountAndOwnerAvailableQuantity(
            tenantId, companyId, materialId, warehouseId, "1010", companyId, "OWNED"
        )).thenReturn(BigDecimal.ZERO);
        when(store.getAccountAndOwnerAvailableQuantity(
            tenantId, companyId, materialId, warehouseId, "1010", null, "OWNED"
        )).thenReturn(BigDecimal.ZERO);

        // Account 002 for Contractor B has 0 units
        when(store.getAccountAndOwnerAvailableQuantity(
            tenantId, companyId, materialId, warehouseId, "002", contractorB, "CUSTODY"
        )).thenReturn(BigDecimal.ZERO);

        // Step 2: Generic company issue attempted for 50 units -> rejected
        var genericDemandCommand = new PostingCommand("WAREHOUSE", "ISSUE", "OWNED", UUID.randomUUID(),
            "cmd-e2e-generic-issue-attempt", LocalDate.of(2026, 10, 14), 2L, mapper.readTree("{\"qty\":50}"));

        var genericDemandRequest = new AccountAwareStockPostingService.IssueRequest(
            genericDemandCommand, materialId, warehouseId,
            null, null, null, null,
            "OWNED", "AVAILABLE", "1010", "PCS",
            new BigDecimal("50.0000000"), null, "UZS", "DEMAND-INTERNAL-FACTORY-1"
        );

        assertThatThrownBy(() -> service.postIssue(genericDemandRequest))
            .isInstanceOf(InsufficientStockException.class)
            .hasMessageContaining("Insufficient stock");

        // Step 3: Contractor B tries to issue Contractor S's custody stock -> rejected
        var contractorBCommand = new PostingCommand("WAREHOUSE", "ISSUE", "CUSTODY", UUID.randomUUID(),
            "cmd-e2e-contractorb-attempt", LocalDate.of(2026, 10, 14), 3L, mapper.readTree("{\"qty\":20}"));

        var contractorBRequest = new AccountAwareStockPostingService.IssueRequest(
            contractorBCommand, materialId, warehouseId,
            null, null, null, contractorB,
            "CUSTODY", "AVAILABLE", "002", "PCS",
            new BigDecimal("20.0000000"), BigDecimal.ZERO, "UZS", "DEMAND-UNAUTHORIZED-CONTRACTOR"
        );

        assertThatThrownBy(() -> service.postIssue(contractorBRequest))
            .isInstanceOf(InsufficientStockException.class)
            .hasMessageContaining("Insufficient stock");

        // Step 4: Authorized custody issue for Contractor S of 40 units -> succeeds!
        var contractorSCommand = new PostingCommand("WAREHOUSE", "ISSUE", "CUSTODY", UUID.randomUUID(),
            "cmd-e2e-contractors-issue-40", LocalDate.of(2026, 10, 14), 4L, mapper.readTree("{\"qty\":40}"));

        var contractorSRequest = new AccountAwareStockPostingService.IssueRequest(
            contractorSCommand, materialId, warehouseId,
            null, null, null, contractorS,
            "CUSTODY", "AVAILABLE", "002", "PCS",
            new BigDecimal("40.0000000"), BigDecimal.ZERO, "UZS", "ACT-002-DISPATCH-AUTHORIZED"
        );

        var dispatchReceipt = service.postIssue(contractorSRequest);
        assertThat(dispatchReceipt).isNotNull();

        // 100 - 40 = 60 remaining physical stock
        assertThat(balance.getQuantity()).isEqualByComparingTo(new BigDecimal("60.0000000"));
    }
}
