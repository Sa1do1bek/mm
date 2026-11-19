package com.enterprise.mm.service.transfer;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class TransferLegacyReconciliationServiceTest {
    @Test void ambiguousOrUnbalancedHistoryIsNotBackfilled() {
        assertThat(TransferLegacyReconciliationService.canAutoReconcile(2,1,2,true)).isFalse();
        assertThat(TransferLegacyReconciliationService.canAutoReconcile(1,1,1,false)).isFalse();
        assertThat(TransferLegacyReconciliationService.canAutoReconcile(1,0,0,true)).isFalse();
        assertThat(TransferLegacyReconciliationService.canAutoReconcile(1,2,2,true)).isTrue();
    }
}
