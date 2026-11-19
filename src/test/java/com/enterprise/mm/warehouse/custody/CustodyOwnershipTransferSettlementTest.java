package com.enterprise.mm.warehouse.custody;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.error.InsufficientStockException;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.service.IdempotencyService;
import com.enterprise.mm.service.StockBalanceMutationService;
import com.enterprise.mm.warehouse.posting.AccountAwareStockPostingService;
import com.enterprise.mm.warehouse.posting.PostingEvidenceStore;
import com.enterprise.mm.warehouse.posting.PostingJournalService;
import com.enterprise.mm.warehouse.posting.PostingRule;
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
 * P4 AT05: Custody ownership transfer / settlement:
 * - Purchasing/settling custody stock (Account 002, non-owned) into Company ownership (Account 1010, owned).
 * - Consumes 002 custody stock and creates 1010 owned stock with financial cost layer.
 * - Crucial invariant: Physical on-hand stock quantity is NOT duplicated (stays exactly 40 units).
 * - Financial total value increases from 0 to 40 * 1500 = 60,000 UZS.
 */
class CustodyOwnershipTransferSettlementTest {
    private final PostingEvidenceStore store = mock(PostingEvidenceStore.class);
    private final PostingJournalService journalService = new PostingJournalService(store);
    private final StockBalanceRepository stockBalanceRepository = mock(StockBalanceRepository.class);
    private final StockBalanceMutationService mutationService = new StockBalanceMutationService();
    private final OwnerEligibilityService eligibilityService = new OwnerEligibilityService();
    private final WarehouseCustodyAgreementRepository agreementRepository = mock(WarehouseCustodyAgreementRepository.class);
    private final CustodyAgreementService agreementService = new CustodyAgreementService(agreementRepository, store);
    private final WarehouseCustodyOwnershipTransferRepository transferRepository = mock(WarehouseCustodyOwnershipTransferRepository.class);
    private final IdempotencyService idempotencyService = mock(IdempotencyService.class);

    private final AccountAwareStockPostingService postingService = new AccountAwareStockPostingService(
        journalService,
        stockBalanceRepository,
        mutationService,
        store,
        eligibilityService
    );

    private final CustodyOperationService service = new CustodyOperationService(
        postingService,
        agreementService,
        store,
        transferRepository,
        stockBalanceRepository,
        idempotencyService
    );

    private final String tenantId = "navoiy-azot";
    private final UUID companyId = UUID.randomUUID();
    private final UUID contractorOwnerId = UUID.randomUUID();
    private final UUID materialId = UUID.randomUUID();
    private final UUID warehouseId = UUID.randomUUID();

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
    @DisplayName("P4 AT05: Ownership transfer from 002 to 1010 converts stock to owned asset without physical duplication")
    void ownershipTransferConvertsToOwnedAssetWithoutPhysicalDuplication() {
        var agreement = new WarehouseCustodyAgreement();
        agreement.setId(UUID.randomUUID());
        agreement.setTenantId(tenantId);
        agreement.setCompanyId(companyId);
        agreement.setAgreementNumber("AGR-002-2026-SETTLE");
        agreement.setOwnerId(contractorOwnerId);
        agreement.setCounterpartyName("Trans-Logistics Consignment");
        agreement.setValidFrom(LocalDate.of(2026, 1, 1));
        agreement.setValidThrough(LocalDate.of(2026, 12, 31));
        agreement.setAgreementStatus(CustodyAgreementStatus.ACTIVE);
        agreement.setSettlementPolicy("CONSIGNMENT_BUYOUT");

        when(agreementRepository.findByTenantIdAndCompanyIdAndOwnerId(tenantId, companyId, contractorOwnerId))
            .thenReturn(List.of(agreement));

        // Physical on hand is 40 units, totalValue is 0 (all 40 units are currently under custody)
        var balance = new StockBalance();
        balance.setTenantId(tenantId);
        balance.setCompanyId(companyId);
        balance.setMaterialId(materialId);
        balance.setWarehouseId(warehouseId);
        balance.setQuantity(new BigDecimal("40.0000000"));
        balance.setAvailableQuantity(new BigDecimal("40.0000000"));
        balance.setTotalValue(BigDecimal.ZERO);

        when(stockBalanceRepository.findBalanceForUpdate(
            eq(tenantId), eq(materialId), eq(warehouseId),
            eq(true), eq(null),
            eq(true), eq(null),
            eq(true), eq(null)
        )).thenReturn(Optional.of(balance));

        when(store.reserve(any(), any(), any(), any(), any())).thenReturn(true);

        // Account 002 for contractor has 40 units available
        when(store.getAccountAndOwnerAvailableQuantity(
            tenantId, companyId, materialId, warehouseId, "002", contractorOwnerId, "CUSTODY"
        )).thenReturn(new BigDecimal("40.0000000"));

        var ruleIssue002 = new PostingRule(UUID.randomUUID(), "WAREHOUSE", "ISSUE", "CUSTODY", 1,
            LocalDate.of(2026, 10, 1), null, true, "002", "UZS");
        var ruleReceipt1010 = new PostingRule(UUID.randomUUID(), "WAREHOUSE", "RECEIPT", "OWNED", 1,
            LocalDate.of(2026, 10, 1), null, true, "1010", "UZS");

        when(store.rules(any(), any(), any())).thenAnswer(invocation -> {
            var query = (com.enterprise.mm.warehouse.posting.PostingRuleQuery) invocation.getArgument(2);
            if ("CUSTODY".equals(query.contextKey())) {
                return List.of(new PostingEvidenceStore.RuleEvidence(ruleIssue002, "{}"));
            }
            return List.of(new PostingEvidenceStore.RuleEvidence(ruleReceipt1010, "{}"));
        });

        // Command: Buy out all 40 units of custody stock at 1,500 UZS each into Account 1010
        var command = new CustodyCommands.CustodyOwnershipTransferCommand(
            "cmd-custody-buyout-40",
            materialId,
            warehouseId,
            contractorOwnerId,
            new BigDecimal("40.0000000"),
            new BigDecimal("1500.0000"), // agreed purchase price
            "1010",
            "PCS",
            LocalDate.of(2026, 10, 14),
            "SETTLEMENT-ACT-2026-40"
        );

        var result = service.transferOwnershipToCompany(command);

        assertThat(result).isNotNull();
        assertThat(result.transferredQuantity()).isEqualByComparingTo(new BigDecimal("40.0000000"));
        assertThat(result.totalSettlementValue()).isEqualByComparingTo(new BigDecimal("60000.0000"));
        assertThat(result.targetAccountCode()).isEqualTo("1010");

        // CRITICAL INVARIANT: Physical stock balance quantity did NOT duplicate!
        // 40 units in -> 40 units out -> remains 40 units on hand
        assertThat(balance.getQuantity()).isEqualByComparingTo(new BigDecimal("40.0000000"));

        // Value was capitalized into company balance sheet
        assertThat(balance.getTotalValue()).isEqualByComparingTo(new BigDecimal("60000.0000"));

        // Saved transfer record
        verify(transferRepository).save(any());
    }
}
