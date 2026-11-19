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
import com.enterprise.mm.error.ConflictException;
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
 * P4 AT06: Custody activation and statements:
 * - Approved historical custody activation into 002 is idempotent and preserves source row hashes.
 * - Custody statement maintains separate owned and non-owned valuation bases with zero delta.
 */
class CustodyActivationAndStatementTest {
    private final PostingEvidenceStore store = mock(PostingEvidenceStore.class);
    private final PostingJournalService journalService = new PostingJournalService(store);
    private final StockBalanceRepository stockBalanceRepository = mock(StockBalanceRepository.class);
    private final StockBalanceMutationService mutationService = new StockBalanceMutationService();
    private final OwnerEligibilityService eligibilityService = new OwnerEligibilityService();
    private final WarehouseCustodyAgreementRepository agreementRepository = mock(WarehouseCustodyAgreementRepository.class);
    private final CustodyAgreementService agreementService = new CustodyAgreementService(agreementRepository, store);
    private final WarehouseCustodyOwnershipTransferRepository transferRepository = mock(WarehouseCustodyOwnershipTransferRepository.class);
    private final WarehouseCustodyActivationRepository activationRepository = mock(WarehouseCustodyActivationRepository.class);
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
        activationRepository,
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
    @DisplayName("P4 AT06: Historical 1C account 002 custody activation is idempotent and preserves source hash")
    void historicalCustodyActivationIsIdempotent() {
        var agreement = new WarehouseCustodyAgreement();
        agreement.setId(UUID.randomUUID());
        agreement.setTenantId(tenantId);
        agreement.setCompanyId(companyId);
        agreement.setAgreementNumber("CUST-AGR-HIST-01");
        agreement.setOwnerId(contractorOwnerId);
        agreement.setCounterpartyName("KhimProm Snab");
        agreement.setValidFrom(LocalDate.of(2025, 1, 1));
        agreement.setValidThrough(null); // Open-ended
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

        String sourceRef = "1C-CUSTODY-2026-ROW-9988";
        String sourceHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        var command = new CustodyCommands.ActivateCustodyCommand(
            sourceRef,
            sourceHash,
            materialId,
            warehouseId,
            contractorOwnerId,
            new BigDecimal("120.0000000"),
            new BigDecimal("45000.0000"), // 1C reference value (not company asset)
            "UZS",
            "PCS",
            LocalDate.of(2026, 10, 14)
        );

        // 1. Initial activation: succeeds
        var result1 = service.activateHistoricalCustody(command);
        assertThat(result1).isNotNull();
        assertThat(result1.activatedQuantity()).isEqualByComparingTo(new BigDecimal("120.0000000"));
        assertThat(result1.isIdempotentReplay()).isFalse();

        // 2. Setup mock for already activated record
        var existingActivation = new WarehouseCustodyActivation();
        existingActivation.setSourceReferenceId(sourceRef);
        existingActivation.setSourceHash(sourceHash);
        existingActivation.setQuantity(new BigDecimal("120.0000000"));
        existingActivation.setMaterialId(materialId);
        existingActivation.setWarehouseId(warehouseId);
        existingActivation.setOwnerId(contractorOwnerId);

        when(activationRepository.findByTenantIdAndCompanyIdAndSourceReferenceId(tenantId, companyId, sourceRef))
            .thenReturn(Optional.of(existingActivation));

        // Replaying identical activation returns the same receipt without posting again
        var result2 = service.activateHistoricalCustody(command);
        assertThat(result2).isNotNull();
        assertThat(result2.isIdempotentReplay()).isTrue();
        assertThat(result2.activatedQuantity()).isEqualByComparingTo(new BigDecimal("120.0000000"));

        // 3. Replaying with changed hash throws ConflictException
        var changedCommand = new CustodyCommands.ActivateCustodyCommand(
            sourceRef,
            "changed-tampered-hash-000000000000000000000000000000000000000000000000",
            materialId,
            warehouseId,
            contractorOwnerId,
            new BigDecimal("120.0000000"),
            new BigDecimal("45000.0000"),
            "UZS",
            "PCS",
            LocalDate.of(2026, 10, 14)
        );

        assertThatThrownBy(() -> service.activateHistoricalCustody(changedCommand))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("Source content hash mismatch");
    }

    @Test
    @DisplayName("P4 AT06: Custody statement keeps owned and non-owned valuation bases separate with zero delta")
    void custodyStatementSeparatesValuationBasesWithZeroDelta() {
        var agreement = new WarehouseCustodyAgreement();
        agreement.setId(UUID.randomUUID());
        agreement.setTenantId(tenantId);
        agreement.setCompanyId(companyId);
        agreement.setAgreementNumber("CUST-AGR-STMT-02");
        agreement.setOwnerId(contractorOwnerId);
        agreement.setCounterpartyName("KhimProm Snab");
        agreement.setValidFrom(LocalDate.of(2026, 1, 1));
        agreement.setValidThrough(null);
        agreement.setAgreementStatus(CustodyAgreementStatus.ACTIVE);

        when(agreementRepository.findByTenantIdAndCompanyIdAndOwnerId(tenantId, companyId, contractorOwnerId))
            .thenReturn(List.of(agreement));

        // Subledger available quantity on 002 for contractor is 85 units
        when(store.getAccountAndOwnerAvailableQuantity(
            tenantId, companyId, materialId, warehouseId, "002", contractorOwnerId, "CUSTODY"
        )).thenReturn(new BigDecimal("85.0000000"));

        var query = new CustodyCommands.CustodyStatementQuery(
            contractorOwnerId,
            warehouseId,
            materialId,
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 10, 14)
        );

        var report = service.generateCustodyStatement(query);

        assertThat(report).isNotNull();
        assertThat(report.agreementNumber()).isEqualTo("CUST-AGR-STMT-02");
        assertThat(report.counterpartyName()).isEqualTo("KhimProm Snab");
        assertThat(report.positions()).hasSize(1);

        var position = report.positions().getFirst();
        assertThat(position.accountCode()).isEqualTo("002");
        assertThat(position.stockCategory()).isEqualTo("CUSTODY");
        assertThat(position.closingQuantity()).isEqualByComparingTo(new BigDecimal("85.0000000"));
        assertThat(position.companyAssetValue()).isEqualByComparingTo(BigDecimal.ZERO); // Crucial: 0 company asset value
        assertThat(position.reconciledDelta()).isEqualByComparingTo(BigDecimal.ZERO); // Zero delta
    }
}
