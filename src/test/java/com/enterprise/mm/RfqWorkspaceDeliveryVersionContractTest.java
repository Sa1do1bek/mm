package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.payload.RfqWorkspaceDtos.DeliveryAttempt;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RfqWorkspaceDeliveryVersionContractTest {
    @Test
    void exposesOptimisticDeliveryVersionForScopedResend() {
        DeliveryAttempt delivery = new DeliveryAttempt(
            UUID.randomUUID(),
            7L,
            UUID.randomUUID(),
            "FAILED",
            "supplier@example.test",
            "Supplier Contact",
            2,
            null,
            null,
            null,
            null,
            null,
            null,
            "MAILBOX_REJECTED"
        );

        assertThat(delivery.version()).isEqualTo(7L);
    }
}
