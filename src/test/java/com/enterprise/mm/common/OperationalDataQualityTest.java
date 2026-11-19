package com.enterprise.mm.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** UX-LIVE-09: demo/test/synthetic records must be excluded from operational KPIs. */
class OperationalDataQualityTest {

    @Test
    void detectsDemoOrTestMarkersCaseInsensitivelyInCodes() {
        assertThat(OperationalDataQuality.isDemoOrTestCode("DEMO-PO-1")).isTrue();
        assertThat(OperationalDataQuality.isDemoOrTestCode("test-po-1")).isTrue();
        assertThat(OperationalDataQuality.isDemoOrTestCode("E2E-PO-1")).isTrue();
        assertThat(OperationalDataQuality.isDemoOrTestCode("SYNTHETIC-PO-1")).isTrue();
        assertThat(OperationalDataQuality.isDemoOrTestCode("QA-PO-1")).isTrue();
        assertThat(OperationalDataQuality.isDemoOrTestCode("PO-2026-DEMO-1")).isTrue();
    }

    @Test
    void ignoresRealCodesAndNames() {
        assertThat(OperationalDataQuality.isDemoOrTestCode("PO-2026-01")).isFalse();
        assertThat(OperationalDataQuality.isDemoOrTestCode(null)).isFalse();
        assertThat(OperationalDataQuality.isDemoOrTestCode(" ")).isFalse();
        assertThat(OperationalDataQuality.isDemoOrTestName("Bearing Supplier LLC")).isFalse();
    }

    @Test
    void isOperationalRecordRejectsEitherDemoCodeOrDemoName() {
        assertThat(OperationalDataQuality.isOperationalRecord("PO-2026-01", "Bearing supply")).isTrue();
        assertThat(OperationalDataQuality.isOperationalRecord("DEMO-PO-1", "Bearing supply")).isFalse();
        assertThat(OperationalDataQuality.isOperationalRecord("PO-2026-01", "TEST-supplier")).isFalse();
    }
}
