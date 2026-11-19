package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.enums.PurchaseRequestStatus;
import org.junit.jupiter.api.Test;

class RfqAggregationMigrationArchitectureTest {



    @Test
    void purchaseRequestStatusesIncludeDerivedTenderCoverage() {
        assertThat(PurchaseRequestStatus.values()).contains(
            PurchaseRequestStatus.PARTIALLY_SENT_TO_TENDER,
            PurchaseRequestStatus.FULLY_SENT_TO_TENDER);
    }


}
