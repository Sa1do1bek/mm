package com.enterprise.mm.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DateRangeFormatConfigTest {
    private final DateRangeFormatConfig.IsoDateRangeLocalDateConverter converter = new DateRangeFormatConfig.IsoDateRangeLocalDateConverter();

    @Test
    void acceptsLegacyDateOnlyValues() {
        assertThat(converter.convert("2026-06-15")).isEqualTo(LocalDate.of(2026, 6, 15));
    }

    @Test
    void acceptsTimezoneAwareIsoDateTimesFromDataPage() {
        assertThat(converter.convert("2026-06-15T00:00:00+05:00")).isEqualTo(LocalDate.of(2026, 6, 15));
        assertThat(converter.convert("2026-06-15T23:59:59+05:00")).isEqualTo(LocalDate.of(2026, 6, 15));
    }
}
