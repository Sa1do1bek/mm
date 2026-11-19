package com.enterprise.mm.payload;

import java.time.LocalDate;
import java.util.Locale;
import java.time.temporal.ChronoUnit;

/**
 * Explicit reporting window and optional comparable currency selection.
 * The currency selector never implies exchange-rate conversion.
 */
public record ReportFilter(LocalDate from, LocalDate to, String displayCurrency) {
    public ReportFilter {
        LocalDate effectiveTo = to == null ? LocalDate.now() : to;
        LocalDate effectiveFrom = from == null
            ? effectiveTo.minusMonths(11).withDayOfMonth(1)
            : from;
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new IllegalArgumentException("report period from must not be after to");
        }
        if (ChronoUnit.DAYS.between(effectiveFrom, effectiveTo) > 730) {
            throw new IllegalArgumentException("report period must not exceed 730 days");
        }
        from = effectiveFrom;
        to = effectiveTo;
        if (displayCurrency != null && !displayCurrency.isBlank()) {
            String normalized = displayCurrency.trim().toUpperCase(Locale.ROOT);
            if (!normalized.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException("display currency must be a 3-letter ISO code");
            }
            displayCurrency = normalized;
        } else {
            displayCurrency = null;
        }
    }

    public static ReportFilter defaultPeriod() {
        return new ReportFilter(null, null, null);
    }
}
