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
import com.enterprise.mm.error.BadRequestException;
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
 * P4 AT02: Custody receipt into Account 002 with agreement linkage:
 * - Increases physical stock on hand.
 * - Non-owned valuation basis: company monetary asset value remains 0 (does not inflate balance sheet).
 * - Requires a valid, active custody agreement.
 */
class CustodyReceiptNonOwnedValuationTest {
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
    @DisplayName("P4 AT02: Custody receipt increases physical stock with zero company asset valuation")
    void custodyReceiptIncreasesPhysicalStockWithZeroCompanyAssetValuation() {
        var agreement = new WarehouseCustodyAgreement();
        agreement.setId(UUID.randomUUID());
        agreement.setTenantId(tenantId);
        agreement.setCompanyId(companyId);
        agreement.setAgreementNumber("AGR-002-2026-01");
        agreement.setOwnerId(contractorOwnerId);
        agreement.setCounterpartyName("Uzkimyosanoat Contractor");
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

        var ruleReceipt002 = new PostingRule(UUID.randomUUID(), "WAREHOUSE", "RECEIPT", "CUSTODY", 1,
            LocalDate.of(2026, 10, 1), null, true, "002", "UZS");
        when(store.rules(any(), any(), any())).thenReturn(
            List.of(new PostingEvidenceStore.RuleEvidence(ruleReceipt002, "{}"))
        );

        var command = new CustodyCommands.CustodyReceiptCommand(
            "cmd-rcv-custody-001",
            materialId,
            warehouseId,
            contractorOwnerId,
            new BigDecimal("75.0000000"),
            "PCS",
            LocalDate.of(2026, 10, 14),
            "ACT-CUSTODY-RCV-01"
        );

        var result = service.receiveCustodyStock(command);

        assertThat(result).isNotNull();
        assertThat(result.receiptQuantity()).isEqualByComparingTo(new BigDecimal("75.0000000"));
        assertThat(result.accountCode()).isEqualTo("002");
        assertThat(result.stockCategory()).isEqualTo("CUSTODY");
        assertThat(result.financialAssetValue()).isEqualByComparingTo(BigDecimal.ZERO);

        // Physical quantity increased to 75
        assertThat(balance.getQuantity()).isEqualByComparingTo(new BigDecimal("75.0000000"));
        // Total value remains 0
        assertThat(balance.getTotalValue()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
