package com.enterprise.mm.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.enums.IntegrationEventDirection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "app.seed.enabled=false")
@ActiveProfiles("test")
@Transactional
class TreasuryPaymentAcknowledgementRepositoryTest {
    private static final String TENANT = "tenant-treasury-ack";
    private static final List<String> SUCCESSFUL_STATUSES =
        List.of("PAID", "SUCCESS", "SUCCEEDED", "COMPLETED");

    @Autowired private IntegrationEventRepository repository;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void treasuryPaidCallbackMatchesOnlyTheExactCompanyAggregateAndExternalReference() {
        UUID companyId = UUID.randomUUID();
        UUID otherCompanyId = UUID.randomUUID();
        UUID actId = UUID.randomUUID();
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(companyId);

        repository.saveAndFlush(paymentRequest(companyId, actId));
        repository.saveAndFlush(callback(companyId, actId, "BANK-42"));
        repository.saveAndFlush(callback(otherCompanyId, actId, "BANK-42"));
        repository.saveAndFlush(callback(companyId, actId, "BANK-WRONG"));

        assertThat(repository.countSuccessfulTreasuryPaymentAcknowledgements(
            TENANT, companyId, actId, "BANK-42", SUCCESSFUL_STATUSES
        )).isEqualTo(1);
        assertThat(repository.countSuccessfulTreasuryPaymentAcknowledgements(
            TENANT, companyId, actId, "BANK-MISSING", SUCCESSFUL_STATUSES
        )).isZero();
        assertThat(repository.countSuccessfulTreasuryPaymentAcknowledgements(
            TENANT, UUID.randomUUID(), actId, "BANK-42", SUCCESSFUL_STATUSES
        )).isZero();
    }

    @Test
    void treasuryCallbackWithoutAnOutgoingPaymentRequestIsNotPaymentEvidence() {
        UUID companyId = UUID.randomUUID();
        UUID actId = UUID.randomUUID();
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(companyId);

        repository.saveAndFlush(callback(companyId, actId, "BANK-UNSOLICITED"));

        assertThat(repository.countSuccessfulTreasuryPaymentAcknowledgements(
            TENANT, companyId, actId, "BANK-UNSOLICITED", SUCCESSFUL_STATUSES
        )).isZero();
    }

    @Test
    void callerWritableGenericEventIsNotTreasuryPaymentEvidence() {
        UUID companyId = UUID.randomUUID();
        UUID actId = UUID.randomUUID();
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(companyId);

        repository.saveAndFlush(paymentRequest(companyId, actId));
        repository.saveAndFlush(genericEventWithCallbackShapedFields(companyId, actId, "BANK-FORGED"));

        assertThat(repository.countSuccessfulTreasuryPaymentAcknowledgements(
            TENANT, companyId, actId, "BANK-FORGED", SUCCESSFUL_STATUSES
        )).isZero();
    }

    private IntegrationEvent paymentRequest(UUID companyId, UUID aggregateId) {
        IntegrationEvent event = new IntegrationEvent();
        event.setTenantId(TENANT);
        event.setCompanyId(companyId);
        event.setCode("TREASURY-REQUEST-" + UUID.randomUUID());
        event.setName("Treasury payment request");
        event.setDirection(IntegrationEventDirection.OUTBOUND.name());
        event.setStatus("PUBLISHED");
        event.setEventType("TREASURY.PAYMENT_REQUEST");
        event.setAggregateType("TREASURY");
        event.setAggregateId(aggregateId);
        event.setPayload("{}");
        event.setCreatedAt(LocalDateTime.now().minusSeconds(1));
        return event;
    }

    private IntegrationEvent callback(UUID companyId, UUID aggregateId, String externalReference) {
        IntegrationEvent event = new IntegrationEvent();
        event.setTenantId(TENANT);
        event.setCompanyId(companyId);
        event.setCode("TREASURY-CALLBACK-" + UUID.randomUUID());
        event.setName("Treasury payment callback");
        event.setDirection(IntegrationEventDirection.INBOUND.name());
        event.setStatus("PUBLISHED");
        event.setEventType("TREASURY.PAYMENT_STATUS_PAID");
        event.setAggregateType("TREASURY");
        event.setAggregateId(aggregateId);
        event.setExternalId(externalReference);
        event.setDescription("PAID");
        event.setPayload("{}");
        event.setCreatedAt(LocalDateTime.now());
        return event;
    }

    private IntegrationEvent genericEventWithCallbackShapedFields(
        UUID companyId,
        UUID aggregateId,
        String externalReference
    ) {
        IntegrationEvent event = callback(companyId, aggregateId, externalReference);
        event.setCode("GENERIC-EVENT-" + UUID.randomUUID());
        event.setName("Caller-writable generic event");
        event.setDirection(IntegrationEventDirection.OUTBOUND.name());
        event.setStatus("NEW");
        return event;
    }
}
