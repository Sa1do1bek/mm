package com.enterprise.mm.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TrackingModePolicyCompatibilityTest {
    @Test
    void legacyTrackingModesMapToIndependentPolicies() {
        assertThat(TrackingMode.batchTrackingPolicy(TrackingMode.QUANTITY)).isEqualTo(BatchTrackingPolicy.NONE);
        assertThat(TrackingMode.serialTrackingPolicy(TrackingMode.QUANTITY)).isEqualTo(SerialTrackingPolicy.NONE);

        assertThat(TrackingMode.batchTrackingPolicy(TrackingMode.LOT)).isEqualTo(BatchTrackingPolicy.REQUIRED);
        assertThat(TrackingMode.serialTrackingPolicy(TrackingMode.LOT)).isEqualTo(SerialTrackingPolicy.NONE);

        assertThat(TrackingMode.batchTrackingPolicy(TrackingMode.SERIAL)).isEqualTo(BatchTrackingPolicy.NONE);
        assertThat(TrackingMode.serialTrackingPolicy(TrackingMode.SERIAL)).isEqualTo(SerialTrackingPolicy.REQUIRED);
    }

    @Test
    void legacyProjectionPreservesSerializedBehaviorForBatchAndSerialMaterials() {
        assertThat(TrackingMode.fromPolicies(BatchTrackingPolicy.NONE, SerialTrackingPolicy.NONE))
            .isEqualTo(TrackingMode.QUANTITY);
        assertThat(TrackingMode.fromPolicies(BatchTrackingPolicy.REQUIRED, SerialTrackingPolicy.NONE))
            .isEqualTo(TrackingMode.LOT);
        assertThat(TrackingMode.fromPolicies(BatchTrackingPolicy.NONE, SerialTrackingPolicy.REQUIRED))
            .isEqualTo(TrackingMode.SERIAL);
        assertThat(TrackingMode.fromPolicies(BatchTrackingPolicy.REQUIRED, SerialTrackingPolicy.REQUIRED))
            .isEqualTo(TrackingMode.SERIAL);
    }
}
