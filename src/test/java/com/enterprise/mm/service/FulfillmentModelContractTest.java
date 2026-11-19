package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.fulfillment.FulfillmentDisposition;
import com.enterprise.mm.fulfillment.FulfillmentLifecycle;
import org.junit.jupiter.api.Test;

class FulfillmentModelContractTest {
    @Test
    void goodsDispositionsSeparateReceiptFromAcceptance() {
        assertThat(FulfillmentDisposition.values())
            .extracting(Enum::name)
            .containsExactly(
                "RECEIVED_PENDING_INSPECTION",
                "ACCEPTED",
                "REJECTED",
                "RETURNED",
                "RESOLVED_BY_EXCEPTION"
            );
    }

    @Test
    void lifecycleSeparatesOperationalAndFinancialStates() {
        assertThat(FulfillmentLifecycle.operationalStates())
            .containsExactly("OPEN", "PARTIALLY_FULFILLED", "FULFILLED");
        assertThat(FulfillmentLifecycle.financialStates())
            .containsExactly("UNINVOICED", "PARTIALLY_INVOICED", "INVOICED", "PARTIALLY_PAID", "PAID");
    }
}
