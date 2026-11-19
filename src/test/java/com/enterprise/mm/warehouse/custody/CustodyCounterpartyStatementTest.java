package com.enterprise.mm.warehouse.custody;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.warehouse.posting.PostingEvidenceStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PR 4B: Custody Counterparty Reconciliation Statement:
 * Queries all custody positions on Account 002 for a counterparty,
 * proving that opening + received - dispatched - returned - current == 0 (zero delta).
 */
class CustodyCounterpartyStatementTest {
    private final WarehouseCustodyAgreementRepository agreementRepository = mock(WarehouseCustodyAgreementRepository.class);
    private final PostingEvidenceStore store = mock(PostingEvidenceStore.class);
    private final CustodyAgreementService service = new CustodyAgreementService(agreementRepository, store);

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
    @DisplayName("PR 4B: Counterparty custody statement accurately reconciles account 002 with zero delta")
    void counterpartyCustodyStatementReconcilesAccurately() {
        var agreement = new WarehouseCustodyAgreement();
        agreement.setId(UUID.randomUUID());
        agreement.setTenantId(tenantId);
        agreement.setCompanyId(companyId);
        agreement.setAgreementNumber("CUST-2026-NAV-01");
        agreement.setOwnerId(contractorOwnerId);
        agreement.setCounterpartyName("KhimMash Stroy LLC");
        agreement.setValidFrom(LocalDate.of(2026, 1, 1));
        agreement.setValidThrough(LocalDate.of(2026, 12, 31));
        agreement.setAgreementStatus(CustodyAgreementStatus.ACTIVE);
        agreement.setSettlementPolicy("STORAGE_FEE");

        when(agreementRepository.findByTenantIdAndCompanyIdAndOwnerId(tenantId, companyId, contractorOwnerId))
            .thenReturn(List.of(agreement));

        // In subledger posting evidence for Contractor on Account 002:
        // 100 units received, 40 units dispatched, 10 units returned, 50 units remaining on hand
        when(store.getAccountAndOwnerAvailableQuantity(
            tenantId, companyId, materialId, warehouseId, "002", contractorOwnerId, "CUSTODY"
        )).thenReturn(new BigDecimal("50.0000000"));

        var query = new CustodyAgreementService.CounterpartyStatementQuery(
            contractorOwnerId,
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 10, 14),
            warehouseId,
            materialId
        );

        var report = service.getCounterpartyStatement(query);

        assertThat(report).isNotNull();
        assertThat(report.agreementNumber()).isEqualTo("CUST-2026-NAV-01");
        assertThat(report.counterpartyName()).isEqualTo("KhimMash Stroy LLC");
        assertThat(report.positions()).hasSize(1);

        var pos = report.positions().getFirst();
        assertThat(pos.accountCode()).isEqualTo("002");
        assertThat(pos.stockCategory()).isEqualTo("CUSTODY");
        assertThat(pos.closingQuantity()).isEqualByComparingTo(new BigDecimal("50.0000000"));
        assertThat(pos.reconciledDelta()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
