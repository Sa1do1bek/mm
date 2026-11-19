package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.enums.ProcurementMethodType;
import com.enterprise.mm.enums.ProcurementExecutionPath;
import com.enterprise.mm.error.BusinessException;
import org.junit.jupiter.api.Test;

class ProcurementMethodPolicyTest {
    private final ProcurementMethodPolicy policy = new ProcurementMethodPolicy();

    @Test
    void mapsLegacySingleSupplier() {
        assertThat(policy.normalize("SINGLE_SUPPLIER")).isEqualTo("SINGLE_SOURCE");
    }

    @Test
    void mapsLegacyStrategyValues() {
        assertThat(policy.normalize("COMPETITIVE_TENDER")).isEqualTo("OPEN_TENDER");
        assertThat(policy.normalize("SPOT_BUY")).isEqualTo("DIRECT_PURCHASE");
    }

    @Test
    void normalizesFormatting() {
        assertThat(policy.normalize(" request-for-quotation ")).isEqualTo("REQUEST_FOR_QUOTATION");
    }

    @Test
    void classifiesExecutionChannels() {
        assertThat(ProcurementMethodType.OPEN_TENDER.isCompetitive()).isTrue();
        assertThat(ProcurementMethodType.OPEN_TENDER.usesRfqEngine()).isTrue();
        assertThat(ProcurementMethodType.OPEN_TENDER.requiresPriceDiscovery()).isTrue();
        assertThat(ProcurementMethodType.SINGLE_SOURCE.executionPath()).isEqualTo(ProcurementExecutionPath.DIRECT_AWARD);
        assertThat(ProcurementMethodType.E_AUCTION.executionPath()).isEqualTo(ProcurementExecutionPath.AUCTION);
        assertThat(ProcurementMethodType.FRAME_AGREEMENT.executionPath()).isEqualTo(ProcurementExecutionPath.FRAMEWORK_AGREEMENT);
        assertThat(ProcurementMethodType.E_AUCTION.usesRfqEngine()).isFalse();
        assertThat(ProcurementMethodType.SINGLE_SOURCE.requiresExceptionReason()).isTrue();
    }

    @Test
    void rejectsUnknownMethods() {
        assertThatThrownBy(() -> policy.normalize("UNKNOWN_METHOD"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Unsupported procurement method");
    }
}
