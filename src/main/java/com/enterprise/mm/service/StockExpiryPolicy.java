package com.enterprise.mm.service;

import com.enterprise.mm.enums.StockExpiryStatus;
import com.enterprise.mm.payload.StockBalanceResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Read-only expiry information; no stock, material status or command policies are mutated. */
@Component
public class StockExpiryPolicy {
    public static final int WARNING_DAYS = 30;
    private final Clock clock;

    @Autowired
    public StockExpiryPolicy(@Value("${app.time-zone:Asia/Tashkent}") String timeZone) {
        this(Clock.system(ZoneId.of(timeZone)));
    }

    public StockExpiryPolicy(Clock clock) { this.clock = clock; }

    public LocalDate today() { return LocalDate.now(clock); }
    public java.time.ZoneId zoneId() { return clock.getZone(); }

    public record Info(LocalDate expirationDate, Long daysUntilExpiry, StockExpiryStatus expiryStatus,
        boolean expiryWarning, LocalDate expiryAsOfDate) {}

    public Info evaluate(LocalDate expirationDate, BigDecimal onHandQuantity, LocalDate asOfDate) {
        Long days = expirationDate == null ? null : ChronoUnit.DAYS.between(asOfDate, expirationDate);
        StockExpiryStatus status = days == null ? StockExpiryStatus.UNKNOWN
            : days < 0 ? StockExpiryStatus.EXPIRED
            : days == 0 ? StockExpiryStatus.EXPIRES_TODAY
            : days <= WARNING_DAYS ? StockExpiryStatus.EXPIRING_SOON : StockExpiryStatus.VALID;
        boolean warning = onHandQuantity != null && onHandQuantity.signum() > 0 && days != null && days <= WARNING_DAYS;
        return new Info(expirationDate, days, status, warning, asOfDate);
    }

    public StockBalanceResponse enrich(StockBalanceResponse response, LocalDate asOfDate) {
        Info info = evaluate(response.getExpirationDate(), response.getQuantity(), asOfDate);
        response.setDaysUntilExpiry(info.daysUntilExpiry());
        response.setExpiryStatus(info.expiryStatus());
        response.setExpiryWarning(info.expiryWarning());
        response.setExpiryAsOfDate(info.expiryAsOfDate());
        return response;
    }

    public List<String> filterNames(List<StockExpiryStatus> statuses) {
        // Nonempty IN list works consistently across Hibernate, H2 and PostgreSQL.
        return statuses == null || statuses.isEmpty() ? List.of("_UNFILTERED_")
            : statuses.stream().map(Enum::name).distinct().toList();
    }
}
