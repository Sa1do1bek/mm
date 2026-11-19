package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.enterprise.mm.enums.StockExpiryStatus;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StockExpiryPolicyTest {
    private final LocalDate today = LocalDate.of(2026, 10, 24);
    private final StockExpiryPolicy policy = new StockExpiryPolicy(Clock.fixed(
        Instant.parse("2026-10-24T10:00:00Z"), ZoneId.of("Asia/Tashkent")));

    @ParameterizedTest
    @CsvSource({"-100,EXPIRED,true", "-1,EXPIRED,true", "0,EXPIRES_TODAY,true",
        "1,EXPIRING_SOON,true", "29,EXPIRING_SOON,true", "30,EXPIRING_SOON,true", "31,VALID,false"})
    void boundaries(int offset, StockExpiryStatus expected, boolean warning) {
        var actual = policy.evaluate(today.plusDays(offset), BigDecimal.ONE, policy.today());
        assertThat(actual.daysUntilExpiry()).isEqualTo((long) offset);
        assertThat(actual.expiryStatus()).isEqualTo(expected);
        assertThat(actual.expiryWarning()).isEqualTo(warning);
        assertThat(actual.expiryAsOfDate()).isEqualTo(today);
    }
    @Test void absentDateIsUnknownAndNeverHealthyOrWarning() {
        var info = policy.evaluate(null, BigDecimal.TEN, today);
        assertThat(info.expiryStatus()).isEqualTo(StockExpiryStatus.UNKNOWN);
        assertThat(info.daysUntilExpiry()).isNull();
        assertThat(info.expiryWarning()).isFalse();
    }
    @Test void zeroNegativeOrMissingPhysicalQuantityDoesNotRaiseWarning() {
        for (BigDecimal quantity : new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ONE.negate(), null}) {
            var info = policy.evaluate(today.minusDays(1), quantity, today);
            assertThat(info.expiryStatus()).isEqualTo(StockExpiryStatus.EXPIRED);
            assertThat(info.expiryWarning()).isFalse();
        }
    }
    @Test void businessDateUsesClockZoneAcrossUtcMidnightBoundary() {
        var uz = new StockExpiryPolicy(Clock.fixed(Instant.parse("2026-10-24T20:30:00Z"), ZoneId.of("Asia/Tashkent")));
        assertThat(uz.today()).isEqualTo(today.plusDays(1));
        assertThat(uz.evaluate(today, BigDecimal.ONE, uz.today()).expiryStatus()).isEqualTo(StockExpiryStatus.EXPIRED);
    }
}
