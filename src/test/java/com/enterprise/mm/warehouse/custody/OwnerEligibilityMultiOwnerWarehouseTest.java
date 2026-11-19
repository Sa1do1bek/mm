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
 * P4 AT04: Multi-owner warehouse isolation:
 * When both Company-owned stock (Account 1010) and Contractor-owned custody stock (Account 002)
 * exist for the same item in the same warehouse, operations with owner=Company only touch Company stock,
 * and operations with owner=Contractor only touch Contractor stock. Never cross-allocate or aggregate.
 */
class OwnerEligibilityMultiOwnerWarehouseTest {
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
    private final UUID otherContractorId = UUID.randomUUID();
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
    @DisplayName("P4 AT04: Multi-owner warehouse accurately separates Company 1010 and Contractor 002 stock")
    void multiOwnerWarehouseAccuratelySeparatesStock() throws Exception {
        // Physical stock on hand has 80 units (50 Company-owned + 30 Contractor custody)
        var balance = new StockBalance();
        balance.setTenantId(tenantId);
        balance.setCompanyId(companyId);
        balance.setMaterialId(materialId);
        balance.setWarehouseId(warehouseId);
        balance.setQuantity(new BigDecimal("80.0000000"));
        balance.setAvailableQuantity(new BigDecimal("80.0000000"));
        balance.setTotalValue(new BigDecimal("50000.0000")); // 50 units @ 1000 company asset value

        when(stockBalanceRepository.findBalanceForUpdate(
            eq(tenantId), eq(materialId), eq(warehouseId),
            eq(true), eq(null),
            eq(true), eq(null),
            eq(true), eq(null)
        )).thenReturn(Optional.of(balance));

        // Setup store mock:
        // Account 1010 for Company (OWNED) has 50 units
        when(store.getAccountAndOwnerAvailableQuantity(
            tenantId, companyId, materialId, warehouseId, "1010", companyId, "OWNED"
        )).thenReturn(new BigDecimal("50.0000000"));
        when(store.getAccountAndOwnerAvailableQuantity(
            tenantId, companyId, materialId, warehouseId, "1010", null, "OWNED"
        )).thenReturn(new BigDecimal("50.0000000"));

        // Account 002 for contractorOwnerId (CUSTODY) has 30 units
        when(store.getAccountAndOwnerAvailableQuantity(
            tenantId, companyId, materialId, warehouseId, "002", contractorOwnerId, "CUSTODY"
        )).thenReturn(new BigDecimal("30.0000000"));

        // Account 002 for otherContractorId (CUSTODY) has 0 units
        when(store.getAccountAndOwnerAvailableQuantity(
            tenantId, companyId, materialId, warehouseId, "002", otherContractorId, "CUSTODY"
        )).thenReturn(BigDecimal.ZERO);

        when(store.reserve(any(), any(), any(), any(), any())).thenReturn(true);

        var rule1010 = new PostingRule(UUID.randomUUID(), "WAREHOUSE", "ISSUE", "OWNED", 1,
            LocalDate.of(2026, 10, 1), null, true, "1010", "UZS");
        var rule002 = new PostingRule(UUID.randomUUID(), "WAREHOUSE", "ISSUE", "CUSTODY", 1,
            LocalDate.of(2026, 10, 1), null, true, "002", "UZS");

        when(store.rules(any(), any(), any())).thenAnswer(invocation -> {
            var query = (com.enterprise.mm.warehouse.posting.PostingRuleQuery) invocation.getArgument(2);
            if ("CUSTODY".equals(query.contextKey())) {
                return List.of(new PostingEvidenceStore.RuleEvidence(rule002, "{}"));
            }
            return List.of(new PostingEvidenceStore.RuleEvidence(rule1010, "{}"));
        });

        // 1. Issue 20 units for Company from 1010: should succeed
        var companyIssueCommand = new PostingCommand("WAREHOUSE", "ISSUE", "OWNED", UUID.randomUUID(),
            "cmd-company-issue-20", LocalDate.of(2026, 10, 14), 1L, mapper.readTree("{\"qty\":20}"));

        var companyIssueRequest = new AccountAwareStockPostingService.IssueRequest(
            companyIssueCommand, materialId, warehouseId,
            null, null, null, companyId,
            "OWNED", "AVAILABLE", "1010", "PCS",
            new BigDecimal("20.0000000"), new BigDecimal("1000.0000"), "UZS", "DEMAND-COMP-1"
        );

        var companyReceipt = service.postIssue(companyIssueRequest);
        assertThat(companyReceipt).isNotNull();
        assertThat(companyReceipt.effects()).hasSize(3);
        // Physical quantity reduced by 20 -> 60 remaining (30 company + 30 custody)
        assertThat(balance.getQuantity()).isEqualByComparingTo(new BigDecimal("60.0000000"));

        // Update mock store to reflect remaining 30 units for Company
        when(store.getAccountAndOwnerAvailableQuantity(
            tenantId, companyId, materialId, warehouseId, "1010", companyId, "OWNED"
        )).thenReturn(new BigDecimal("30.0000000"));
        when(store.getAccountAndOwnerAvailableQuantity(
            tenantId, companyId, materialId, warehouseId, "1010", null, "OWNED"
        )).thenReturn(new BigDecimal("30.0000000"));

        // 2. Company tries to issue 40 units (more than 30 available on 1010, even though physical is 60)
        var companyOverIssueCommand = new PostingCommand("WAREHOUSE", "ISSUE", "OWNED", UUID.randomUUID(),
            "cmd-company-overissue-40", LocalDate.of(2026, 10, 14), 2L, mapper.readTree("{\"qty\":40}"));

        var companyOverIssueRequest = new AccountAwareStockPostingService.IssueRequest(
            companyOverIssueCommand, materialId, warehouseId,
            null, null, null, companyId,
            "OWNED", "AVAILABLE", "1010", "PCS",
            new BigDecimal("40.0000000"), new BigDecimal("1000.0000"), "UZS", "DEMAND-COMP-2"
        );

        assertThatThrownBy(() -> service.postIssue(companyOverIssueRequest))
            .isInstanceOf(InsufficientStockException.class)
            .hasMessageContaining("Insufficient stock for owner and account");

        // 3. Other contractor tries to issue Contractor A's custody stock: rejected
        var otherContractorCommand = new PostingCommand("WAREHOUSE", "ISSUE", "CUSTODY", UUID.randomUUID(),
            "cmd-other-contractor-issue", LocalDate.of(2026, 10, 14), 3L, mapper.readTree("{\"qty\":10}"));

        var otherContractorRequest = new AccountAwareStockPostingService.IssueRequest(
            otherContractorCommand, materialId, warehouseId,
            null, null, null, otherContractorId,
            "CUSTODY", "AVAILABLE", "002", "PCS",
            new BigDecimal("10.0000000"), BigDecimal.ZERO, "UZS", "CUSTODY-OTHER-1"
        );

        assertThatThrownBy(() -> service.postIssue(otherContractorRequest))
            .isInstanceOf(InsufficientStockException.class)
            .hasMessageContaining("Insufficient stock for owner and account");

        // 4. Authorized issue for Contractor A for 15 units: succeeds!
        var contractorIssueCommand = new PostingCommand("WAREHOUSE", "ISSUE", "CUSTODY", UUID.randomUUID(),
            "cmd-contractor-issue-15", LocalDate.of(2026, 10, 14), 4L, mapper.readTree("{\"qty\":15}"));

        var contractorIssueRequest = new AccountAwareStockPostingService.IssueRequest(
            contractorIssueCommand, materialId, warehouseId,
            null, null, null, contractorOwnerId,
            "CUSTODY", "AVAILABLE", "002", "PCS",
            new BigDecimal("15.0000000"), BigDecimal.ZERO, "UZS", "CUSTODY-DISPATCH-1"
        );

        var contractorReceipt = service.postIssue(contractorIssueRequest);
        assertThat(contractorReceipt).isNotNull();
        // Physical quantity reduced by 15 -> 45 remaining (30 company + 15 custody)
        assertThat(balance.getQuantity()).isEqualByComparingTo(new BigDecimal("45.0000000"));
    }
}
