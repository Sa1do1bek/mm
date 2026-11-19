package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ProcurementAwardOutcome;
import com.enterprise.mm.enums.CommercialInstrumentType;
import com.enterprise.mm.enums.ProcurementAwardSourceType;
import com.enterprise.mm.repository.ProcurementAwardOutcomeRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CommercialInstrumentTypeResolverTest {
    @Mock private ProcurementAwardOutcomeRepository awardOutcomeRepository;

    private CommercialInstrumentTypeResolver resolver;
    private UUID companyId;
    private UUID purchaseOrderId;
    private UUID contractId;

    @BeforeEach
    void setUp() {
        companyId = CompanyContext.DEFAULT_COMPANY_ID;
        purchaseOrderId = UUID.randomUUID();
        contractId = UUID.randomUUID();
        TenantContext.setTenant("default");
        CompanyContext.setCompany(companyId);
        resolver = new CommercialInstrumentTypeResolver(awardOutcomeRepository);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void keepsExplicitType() {
        assertThat(resolver.resolve(CommercialInstrumentType.STANDALONE_PO, contractId, purchaseOrderId))
            .isEqualTo(CommercialInstrumentType.STANDALONE_PO);
    }

    @Test
    void resolvesStandaloneWhenNoContractButHasPurchaseOrder() {
        assertThat(resolver.resolve(null, null, purchaseOrderId))
            .isEqualTo(CommercialInstrumentType.STANDALONE_PO);
    }

    @Test
    void resolvesFrameworkCallOffFromAwardOutcome() {
        ProcurementAwardOutcome outcome = new ProcurementAwardOutcome();
        outcome.setSourceType(ProcurementAwardSourceType.FRAMEWORK_CALLOFF);
        when(awardOutcomeRepository
            .findFirstByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByFinalizedAtDesc(
                "default", companyId, purchaseOrderId))
            .thenReturn(Optional.of(outcome));

        assertThat(resolver.resolve(null, contractId, purchaseOrderId))
            .isEqualTo(CommercialInstrumentType.FRAMEWORK_CALLOFF);
    }

    @Test
    void resolvesContractCallOffWhenAwardIsNotFramework() {
        ProcurementAwardOutcome outcome = new ProcurementAwardOutcome();
        outcome.setSourceType(ProcurementAwardSourceType.RFQ_QUOTATION);
        when(awardOutcomeRepository
            .findFirstByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByFinalizedAtDesc(
                "default", companyId, purchaseOrderId))
            .thenReturn(Optional.of(outcome));

        assertThat(resolver.resolve(null, contractId, purchaseOrderId))
            .isEqualTo(CommercialInstrumentType.CONTRACT_CALLOFF);
    }

    @Test
    void resolvesContractCallOffWhenNoAwardOutcome() {
        when(awardOutcomeRepository
            .findFirstByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByFinalizedAtDesc(
                "default", companyId, purchaseOrderId))
            .thenReturn(Optional.empty());

        assertThat(resolver.resolve(null, contractId, purchaseOrderId))
            .isEqualTo(CommercialInstrumentType.CONTRACT_CALLOFF);
    }
}
