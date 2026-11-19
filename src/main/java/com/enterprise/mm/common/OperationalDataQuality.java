package com.enterprise.mm.common;

import java.util.List;
import java.util.Locale;

/**
 * UX-LIVE-09: excludes demo/test/synthetic records from operational KPI aggregations
 * (overview cohorts, exception samples, report queries) so dashboards reflect real
 * business activity instead of seed/QA data left in shared environments.
 */
public final class OperationalDataQuality {
    private static final List<String> MARKERS = List.of(
        "DEMO-", "TEST-", "E2E-", "SYNTHETIC-", "QA-");

    private OperationalDataQuality() {
    }

    public static boolean isDemoOrTestCode(String code) {
        return matches(code);
    }

    public static boolean isDemoOrTestName(String name) {
        return matches(name);
    }

    public static boolean isOperationalRecord(String code, String name) {
        return !isDemoOrTestCode(code) && !isDemoOrTestName(name);
    }

    private static boolean matches(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return MARKERS.stream().anyMatch(normalized::contains);
    }
}
