package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.IntegrationEventRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TreasuryPaymentAcknowledgementPolicyTest {
    @Mock private IntegrationEventRepository integrationEventRepository;

    private TreasuryPaymentAcknowledgementPolicy policy;
    private UUID companyId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        TenantContext.setTenant("tenant-payment-ack");
        CompanyContext.setCompany(companyId);
        policy = new TreasuryPaymentAcknowledgementPolicy(integrationEventRepository);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void acceptsOnlyACompanyScopedSuccessfulTreasuryAcknowledgementForTheExactActAndReference() {
        UUID actId = UUID.randomUUID();
        when(integrationEventRepository.countSuccessfulTreasuryPaymentAcknowledgements(
            "tenant-payment-ack", companyId, actId, "BANK-42", List.of("PAID", "SUCCESS", "SUCCEEDED", "COMPLETED")
        )).thenReturn(1L);

        policy.requireSuccessfulAcknowledgement(actId, " BANK-42 ");

        verify(integrationEventRepository).countSuccessfulTreasuryPaymentAcknowledgements(
            "tenant-payment-ack", companyId, actId, "BANK-42", List.of("PAID", "SUCCESS", "SUCCEEDED", "COMPLETED")
        );
    }

    @Test
    void rejectsPaymentWithoutAConfirmedTreasuryCallback() {
        UUID actId = UUID.randomUUID();
        when(integrationEventRepository.countSuccessfulTreasuryPaymentAcknowledgements(
            "tenant-payment-ack", companyId, actId, "BANK-404", List.of("PAID", "SUCCESS", "SUCCEEDED", "COMPLETED")
        )).thenReturn(0L);

        assertThatThrownBy(() -> policy.requireSuccessfulAcknowledgement(actId, "BANK-404"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Treasury payment acknowledgement");
    }
}
