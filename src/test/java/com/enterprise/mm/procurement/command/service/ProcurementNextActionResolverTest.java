package com.enterprise.mm.procurement.command.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.CaseFacts;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProcurementNextActionResolverTest {

    private final ProcurementNextActionResolver resolver = new ProcurementNextActionResolver();

    @Test
    void approvalPendingDisablesStartSourcing() {
        CaseFacts facts = CaseFacts.builder()
            .purchaseRequestStatus("APPROVAL_PENDING")
            .approvalPending(true)
            .build();

        assertThat(resolver.resolveActions(facts, List.of()).primary().key())
            .isEqualTo("REVIEW_APPROVAL");
    }

    @Test
    void approvedRequestAllowsStartSourcing() {
        CaseFacts facts = CaseFacts.builder()
            .purchaseRequestStatus("APPROVED")
            .approvalPending(false)
            .build();

        assertThat(resolver.resolveActions(facts, List.of()).primary().key())
            .isEqualTo("START_SOURCING");
    }
}
