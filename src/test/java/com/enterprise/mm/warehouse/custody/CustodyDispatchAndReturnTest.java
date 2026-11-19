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
 * P4 AT03: Custody dispatch / return back to owner:
 * - Decreases 002 custody stock and physical warehouse on-hand stock.
 * - Prevents over-return (attempting to dispatch more than remaining 002 custody stock).
 */
class CustodyDispatchAndReturnTest {
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
    @DisplayName("P4 AT03: Custody dispatch decreases 002 stock and physical balance, rejects over-return")
    void custodyDispatchDecreasesStockAndRejectsOverReturn() {
        var agreement = new WarehouseCustodyAgreement();
        agreement.setId(UUID.randomUUID());
        agreement.setTenantId(tenantId);
        agreement.setCompanyId(companyId);
        agreement.setAgreementNumber("AGR-002-2026-02");
        agreement.setOwnerId(contractorOwnerId);
        agreement.setCounterpartyName("KhimMash Contractor");
        agreement.setValidFrom(LocalDate.of(2026, 1, 1));
        agreement.setValidThrough(LocalDate.of(2026, 12, 31));
        agreement.setAgreementStatus(CustodyAgreementStatus.ACTIVE);

        when(agreementRepository.findByTenantIdAndCompanyIdAndOwnerId(tenantId, companyId, contractorOwnerId))
            .thenReturn(List.of(agreement));

        var balance = new StockBalance();
        balance.setTenantId(tenantId);
        balance.setCompanyId(companyId);
        balance.setMaterialId(materialId);
        balance.setWarehouseId(warehouseId);
        balance.setQuantity(new BigDecimal("50.0000000"));
        balance.setAvailableQuantity(new BigDecimal("50.0000000"));
        balance.setTotalValue(BigDecimal.ZERO);

        when(stockBalanceRepository.findBalanceForUpdate(
            eq(tenantId), eq(materialId), eq(warehouseId),
            eq(true), eq(null),
            eq(true), eq(null),
            eq(true), eq(null)
        )).thenReturn(Optional.of(balance));

        when(store.reserve(any(), any(), any(), any(), any())).thenReturn(true);

        // Account 002 for contractor has 50 units
        when(store.getAccountAndOwnerAvailableQuantity(
            tenantId, companyId, materialId, warehouseId, "002", contractorOwnerId, "CUSTODY"
        )).thenReturn(new BigDecimal("50.0000000"));

        var ruleIssue002 = new PostingRule(UUID.randomUUID(), "WAREHOUSE", "ISSUE", "CUSTODY", 1,
            LocalDate.of(2026, 10, 1), null, true, "002", "UZS");
        when(store.rules(any(), any(), any())).thenReturn(
            List.of(new PostingEvidenceStore.RuleEvidence(ruleIssue002, "{}"))
        );

        // 1. Dispatch 30 units: succeeds
        var command = new CustodyCommands.CustodyDispatchCommand(
            "cmd-dispatch-custody-001",
            materialId,
            warehouseId,
            contractorOwnerId,
            new BigDecimal("30.0000000"),
            "PCS",
            LocalDate.of(2026, 10, 14),
            "DISPATCH-ACT-01"
        );

        var result = service.dispatchCustodyStock(command);
        assertThat(result).isNotNull();
        assertThat(result.dispatchedQuantity()).isEqualByComparingTo(new BigDecimal("30.0000000"));

        // Physical quantity reduced to 20
        assertThat(balance.getQuantity()).isEqualByComparingTo(new BigDecimal("20.0000000"));

        // 2. Over-return attempt: caller asks to dispatch 30 when only 20 remain
        when(store.getAccountAndOwnerAvailableQuantity(
            tenantId, companyId, materialId, warehouseId, "002", contractorOwnerId, "CUSTODY"
        )).thenReturn(new BigDecimal("20.0000000"));

        var overCommand = new CustodyCommands.CustodyDispatchCommand(
            "cmd-dispatch-custody-over",
            materialId,
            warehouseId,
            contractorOwnerId,
            new BigDecimal("30.0000000"),
            "PCS",
            LocalDate.of(2026, 10, 14),
            "DISPATCH-ACT-OVER"
        );

        assertThatThrownBy(() -> service.dispatchCustodyStock(overCommand))
            .isInstanceOf(InsufficientStockException.class)
            .hasMessageContaining("Insufficient stock for owner and account");
    }
}
