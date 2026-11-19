package com.enterprise.mm.supply.workspace.service;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.supply.workspace.domain.SupplyWorkspaceQuery;
import com.enterprise.mm.supply.workspace.enums.SupplyDataQuality;
import com.enterprise.mm.supply.workspace.enums.SupplyLens;
import com.enterprise.mm.supply.workspace.enums.SupplyPerformanceMetricKey;
import com.enterprise.mm.supply.workspace.enums.SupplySourceState;
import com.enterprise.mm.supply.workspace.payload.SupplyPerformanceDtos.DenominatorMetadata;
import com.enterprise.mm.supply.workspace.payload.SupplyPerformanceDtos.PerformancePoint;
import com.enterprise.mm.supply.workspace.payload.SupplyPerformanceDtos.PerformanceSeries;
import com.enterprise.mm.supply.workspace.payload.SupplyPerformanceDtos.SupplyPerformanceResponse;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.AppliedScope;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.PartialError;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.SourceFreshness;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.UnavailableReason;
import com.enterprise.mm.supply.workspace.repository.SupplyPerformanceQueryRepository;
import com.enterprise.mm.supply.workspace.repository.SupplyPerformanceQueryRepository.DurationObservation;
import com.enterprise.mm.supply.workspace.repository.SupplyPerformanceQueryRepository.LandedCostObservation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SupplyPerformanceService {
    private static final int MAX_ISO_WEEKS = 13;
    private static final long MAX_RANGE_DAYS = 93;
    private static final int MINIMUM_SAMPLE_SIZE = 5;
    private static final String DEFINITION_VERSION = "supply-workspace.v1";

    private final SupplyPerformanceQueryRepository repository;
    private final SupplyWorkspaceAccessPolicy accessPolicy;
    private final Clock clock;

    public SupplyPerformanceService(
        SupplyPerformanceQueryRepository repository,
        SupplyWorkspaceAccessPolicy accessPolicy,
        Clock clock
    ) {
        this.repository = repository;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    public SupplyPerformanceResponse performance(SupplyWorkspaceQuery query) {
        if (query == null || query.asOfDate() == null || query.timeZone() == null) {
            throw bad(
                "SUPPLY_PERFORMANCE_QUERY_REQUIRED",
                "Normalized Supply performance query with asOf and timezone is required");
        }
        accessPolicy.requireReadable(query.lens());
        SupplyWorkspaceQuery bound = accessPolicy.bindAuthenticatedIdentity(query);
        if (bound == null) {
            throw new IllegalStateException("Authenticated Supply performance query is required");
        }

        PerformanceRange range = range(bound);
        SupplyWorkspaceQuery effective = withRange(bound, range.from(), range.to());
        Instant generatedAt = clock.instant();
        List<PerformanceSeries> series = new ArrayList<>();
        List<SourceFreshness> freshness = new ArrayList<>();
        List<PartialError> partialErrors = new ArrayList<>();

        List<UnavailableReason> unsupportedFilters = unsupportedFilters(effective);
        if (!unsupportedFilters.isEmpty()) {
            series.add(unavailableForFilter(
                SupplyPerformanceMetricKey.ASN_CONFIRMATION, "HOURS", unsupportedFilters));
            series.add(unavailableForFilter(
                SupplyPerformanceMetricKey.EXCEPTION_RESOLUTION, "HOURS", unsupportedFilters));
            series.add(unavailableForFilter(
                SupplyPerformanceMetricKey.LANDED_COST_UPLIFT, "PERCENT", unsupportedFilters));
            for (String source : List.of(
                "ADVANCE_SHIPPING_NOTICE", "LANDED_LOGISTICS_EVENT", "IMPORT_DELIVERY")) {
                freshness.add(new SourceFreshness(
                    source, generatedAt, null, SupplySourceState.UNAVAILABLE,
                    "supply.performance.filter.unsupported"));
            }
            series.add(otifUnavailable());
            freshness.add(new SourceFreshness(
                "OTIF", generatedAt, null, SupplySourceState.UNAVAILABLE,
                "supply.performance.otif.receiptAllocationIncomplete"));
            return response(effective, series, freshness, partialErrors, generatedAt);
        }

        try {
            series.add(durationSeries(
                SupplyPerformanceMetricKey.ASN_CONFIRMATION,
                "CONFIRMED_AT",
                "UNCONFIRMED",
                repository.asnConfirmationObservations(effective),
                range,
                effective.timeZone()));
            freshness.add(fresh("ADVANCE_SHIPPING_NOTICE", generatedAt));
        } catch (RuntimeException exception) {
            series.add(failedSeries(SupplyPerformanceMetricKey.ASN_CONFIRMATION, "HOURS"));
            failed("ADVANCE_SHIPPING_NOTICE", generatedAt, freshness, partialErrors);
        }

        try {
            series.add(durationSeries(
                SupplyPerformanceMetricKey.EXCEPTION_RESOLUTION,
                "RESOLVED_AT",
                "UNRESOLVED",
                repository.exceptionResolutionObservations(effective),
                range,
                effective.timeZone()));
            freshness.add(fresh("LANDED_LOGISTICS_EVENT", generatedAt));
        } catch (RuntimeException exception) {
            series.add(failedSeries(SupplyPerformanceMetricKey.EXCEPTION_RESOLUTION, "HOURS"));
            failed("LANDED_LOGISTICS_EVENT", generatedAt, freshness, partialErrors);
        }

        try {
            series.add(landedCostSeries(
                repository.landedCostUpliftObservations(effective), range));
            freshness.add(fresh("IMPORT_DELIVERY", generatedAt));
        } catch (RuntimeException exception) {
            series.add(failedSeries(SupplyPerformanceMetricKey.LANDED_COST_UPLIFT, "PERCENT"));
            failed("IMPORT_DELIVERY", generatedAt, freshness, partialErrors);
        }

        series.add(otifUnavailable());
        freshness.add(new SourceFreshness(
            "OTIF", generatedAt, null, SupplySourceState.UNAVAILABLE,
            "supply.performance.otif.receiptAllocationIncomplete"));

        return response(effective, series, freshness, partialErrors, generatedAt);
    }

    private SupplyPerformanceResponse response(
        SupplyWorkspaceQuery effective,
        List<PerformanceSeries> series,
        List<SourceFreshness> freshness,
        List<PartialError> partialErrors,
        Instant generatedAt
    ) {
        return new SupplyPerformanceResponse(
            new AppliedScope(
                effective.tenantId(), effective.authorizedCompanyIds(),
                effective.authorizedWarehouseIds(), effective.warehouseScoped()),
            SupplyWorkspaceQueryService.appliedFilters(effective),
            series,
            freshness,
            partialErrors,
            generatedAt,
            effective.timeZone().getId(),
            DEFINITION_VERSION);
    }

    private List<UnavailableReason> unsupportedFilters(SupplyWorkspaceQuery query) {
        List<UnavailableReason> reasons = new ArrayList<>();
        if (query.lens() == SupplyLens.MY_WORK) {
            reasons.add(reason(
                "MY_WORK_FILTER_UNSUPPORTED",
                "supply.performance.filter.myWorkUnsupported"));
        }
        if (query.ownerUserId() != null) {
            reasons.add(reason(
                "OWNER_FILTER_UNSUPPORTED",
                "supply.performance.filter.ownerUnsupported"));
        }
        if (query.risk() != null) {
            reasons.add(reason(
                "RISK_FILTER_UNSUPPORTED",
                "supply.performance.filter.riskUnsupported"));
        }
        if (query.queue() != null) {
            reasons.add(reason(
                "QUEUE_FILTER_UNSUPPORTED",
                "supply.performance.filter.queueUnsupported"));
        }
        return List.copyOf(reasons);
    }

    private PerformanceSeries unavailableForFilter(
        SupplyPerformanceMetricKey key,
        String unit,
        List<UnavailableReason> reasons
    ) {
        return new PerformanceSeries(
            key, unit, SupplyDataQuality.UNAVAILABLE, List.of(), null, reasons, DEFINITION_VERSION);
    }

    private PerformanceSeries durationSeries(
        SupplyPerformanceMetricKey key,
        String cohortDateBasis,
        String incompleteReason,
        List<DurationObservation> requested,
        PerformanceRange range,
        ZoneId timeZone
    ) {
        List<DurationObservation> observations = requested == null ? List.of() : requested;
        Map<LocalDate, List<BigDecimal>> valuesByWeek = new LinkedHashMap<>();
        Map<String, Long> excluded = new LinkedHashMap<>();
        long eligibleCount = 0;

        for (DurationObservation observation : observations) {
            if (observation == null) {
                continue;
            }
            LocalDateTime cohortTimestamp = observation.completedAt() == null
                ? observation.startedAt()
                : observation.completedAt();
            if (cohortTimestamp == null || !range.contains(cohortTimestamp.toLocalDate())) {
                continue;
            }
            if (observation.completedAt() == null) {
                increment(excluded, incompleteReason);
                continue;
            }
            if (observation.startedAt() == null) {
                increment(excluded, "MISSING_START_TIMESTAMP");
                continue;
            }
            ZonedDateTime startedAt = observation.startedAt().atZone(timeZone);
            ZonedDateTime completedAt = observation.completedAt().atZone(timeZone);
            Duration duration = Duration.between(startedAt, completedAt);
            if (duration.isNegative()) {
                increment(excluded, "NEGATIVE_DURATION");
                continue;
            }
            BigDecimal hours = BigDecimal.valueOf(duration.toSeconds())
                .divide(BigDecimal.valueOf(3600), 6, RoundingMode.HALF_UP);
            valuesByWeek.computeIfAbsent(isoWeekStart(cohortTimestamp.toLocalDate()), ignored -> new ArrayList<>())
                .add(hours);
            eligibleCount++;
        }

        List<PerformancePoint> points = durationPoints(range, valuesByWeek);
        DenominatorMetadata denominator = denominator(
            eligibleCount, excluded, range, cohortDateBasis);
        return new PerformanceSeries(
            key,
            "HOURS",
            seriesAvailability(points),
            points,
            denominator,
            seriesReasons(points),
            DEFINITION_VERSION);
    }

    private PerformanceSeries landedCostSeries(
        List<LandedCostObservation> requested,
        PerformanceRange range
    ) {
        List<LandedCostObservation> observations = requested == null ? List.of() : requested;
        Map<LocalDate, List<BigDecimal>> valuesByWeek = new LinkedHashMap<>();
        Map<String, Long> excluded = new LinkedHashMap<>();
        long eligibleCount = 0;

        for (LandedCostObservation observation : observations) {
            if (observation == null || observation.cohortDate() == null
                || !range.contains(observation.cohortDate())) {
                continue;
            }
            if (observation.baseGoodsAmount() == null || observation.landedCostAmount() == null) {
                increment(excluded, "MISSING_COST_AMOUNT");
                continue;
            }
            if (observation.baseGoodsAmount().signum() == 0) {
                increment(excluded, "ZERO_BASE_GOODS_AMOUNT");
                continue;
            }
            if (!observation.currencyConsistent()) {
                increment(excluded, "CURRENCY_INCONSISTENT");
                continue;
            }
            if (!observation.postedCostComplete()) {
                increment(excluded, "UNPOSTED_COST_COMPONENT");
                continue;
            }
            BigDecimal percentage = observation.landedCostAmount()
                .subtract(observation.baseGoodsAmount())
                .divide(observation.baseGoodsAmount(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
            valuesByWeek.computeIfAbsent(
                isoWeekStart(observation.cohortDate()), ignored -> new ArrayList<>())
                .add(percentage);
            eligibleCount++;
        }

        List<PerformancePoint> points = valuePoints(range, valuesByWeek);
        return new PerformanceSeries(
            SupplyPerformanceMetricKey.LANDED_COST_UPLIFT,
            "PERCENT",
            seriesAvailability(points),
            points,
            denominator(eligibleCount, excluded, range, "ACTUAL_ARRIVAL_DATE"),
            seriesReasons(points),
            DEFINITION_VERSION);
    }

    private List<PerformancePoint> durationPoints(
        PerformanceRange range,
        Map<LocalDate, List<BigDecimal>> valuesByWeek
    ) {
        List<PerformancePoint> points = new ArrayList<>();
        for (LocalDate weekStart : range.weekStarts()) {
            List<BigDecimal> values = sorted(valuesByWeek.get(weekStart));
            boolean sufficient = values.size() >= MINIMUM_SAMPLE_SIZE;
            points.add(new PerformancePoint(
                weekStart,
                isoWeek(weekStart),
                values.size(),
                null,
                sufficient ? median(values) : null,
                sufficient ? nearestRank(values, 0.90d) : null,
                sufficient ? SupplyDataQuality.AUTHORITATIVE : SupplyDataQuality.UNAVAILABLE,
                sufficient ? List.of() : List.of(insufficient()),
                null));
        }
        return points;
    }

    private List<PerformancePoint> valuePoints(
        PerformanceRange range,
        Map<LocalDate, List<BigDecimal>> valuesByWeek
    ) {
        List<PerformancePoint> points = new ArrayList<>();
        for (LocalDate weekStart : range.weekStarts()) {
            List<BigDecimal> values = sorted(valuesByWeek.get(weekStart));
            boolean sufficient = values.size() >= MINIMUM_SAMPLE_SIZE;
            points.add(new PerformancePoint(
                weekStart,
                isoWeek(weekStart),
                values.size(),
                sufficient ? median(values) : null,
                null,
                null,
                sufficient ? SupplyDataQuality.AUTHORITATIVE : SupplyDataQuality.UNAVAILABLE,
                sufficient ? List.of() : List.of(insufficient()),
                null));
        }
        return points;
    }

    private PerformanceSeries otifUnavailable() {
        return new PerformanceSeries(
            SupplyPerformanceMetricKey.OTIF,
            "PERCENT",
            SupplyDataQuality.UNAVAILABLE,
            List.of(),
            null,
            List.of(reason(
                "RECEIPT_ALLOCATION_INCOMPLETE",
                "supply.performance.otif.receiptAllocationIncomplete")),
            DEFINITION_VERSION);
    }

    private PerformanceSeries failedSeries(SupplyPerformanceMetricKey key, String unit) {
        return new PerformanceSeries(
            key,
            unit,
            SupplyDataQuality.UNAVAILABLE,
            List.of(),
            null,
            List.of(reason("PERFORMANCE_SOURCE_FAILED", "supply.performance.source.failed")),
            DEFINITION_VERSION);
    }

    private void failed(
        String source,
        Instant generatedAt,
        List<SourceFreshness> freshness,
        List<PartialError> errors
    ) {
        freshness.add(new SourceFreshness(
            source, generatedAt, null, SupplySourceState.FAILED,
            "supply.performance.source.failed"));
        errors.add(new PartialError(
            source, "PERFORMANCE_SOURCE_FAILED", "supply.performance.source.failed", true));
    }

    private SourceFreshness fresh(String source, Instant generatedAt) {
        return new SourceFreshness(
            source, generatedAt, generatedAt, SupplySourceState.FRESH,
            "supply.performance.source.fresh");
    }

    private PerformanceRange range(SupplyWorkspaceQuery query) {
        LocalDate from = query.from();
        LocalDate to = query.to();
        if (from == null && to == null) {
            LocalDate currentWeek = isoWeekStart(query.asOfDate());
            from = currentWeek.minusWeeks(MAX_ISO_WEEKS - 1L);
            to = currentWeek.plusWeeks(1);
        } else if (from == null || to == null) {
            throw bad(
                "SUPPLY_PERFORMANCE_RANGE_REQUIRED",
                "Both from and to are required for Supply performance");
        }
        long days = ChronoUnit.DAYS.between(from, to);
        if (days <= 0) {
            throw bad("SUPPLY_PERFORMANCE_RANGE_INVALID", "to must be after from");
        }
        if (days > MAX_RANGE_DAYS) {
            throw bad(
                "SUPPLY_PERFORMANCE_RANGE_TOO_LARGE",
                "Supply performance range cannot exceed 93 days");
        }
        LocalDate firstWeek = isoWeekStart(from);
        LocalDate lastWeek = isoWeekStart(to.minusDays(1));
        long weekCount = ChronoUnit.WEEKS.between(firstWeek, lastWeek) + 1;
        if (weekCount > MAX_ISO_WEEKS) {
            throw bad(
                "SUPPLY_PERFORMANCE_TOO_MANY_ISO_WEEKS",
                "Supply performance range cannot cross more than 13 ISO weeks");
        }
        List<LocalDate> weeks = new ArrayList<>((int) weekCount);
        for (int index = 0; index < weekCount; index++) {
            weeks.add(firstWeek.plusWeeks(index));
        }
        return new PerformanceRange(from, to, List.copyOf(weeks));
    }

    private SupplyWorkspaceQuery withRange(
        SupplyWorkspaceQuery query,
        LocalDate from,
        LocalDate to
    ) {
        return new SupplyWorkspaceQuery(
            query.tenantId(), query.authorizedCompanyIds(), query.authorizedWarehouseIds(),
            query.warehouseScoped(), query.asOf(), query.asOfDate(), query.timeZone(), from, to,
            query.lens(), query.ownerUserId(), query.companyId(), query.supplierId(),
            query.purchaseOrderId(), query.contractId(), query.warehouseId(), query.transportMode(),
            query.currencyCode(), query.status(), query.risk(), query.queue(), query.search(),
            query.sort(), query.authenticatedUserId(), query.authenticatedUsername());
    }

    private DenominatorMetadata denominator(
        long eligibleCount,
        Map<String, Long> excluded,
        PerformanceRange range,
        String cohortDateBasis
    ) {
        long excludedCount = excluded.values().stream().mapToLong(Long::longValue).sum();
        return new DenominatorMetadata(
            eligibleCount,
            excludedCount,
            MINIMUM_SAMPLE_SIZE,
            excluded,
            range.from(),
            range.to(),
            cohortDateBasis);
    }

    private SupplyDataQuality seriesAvailability(List<PerformancePoint> points) {
        boolean hasAuthoritative = points.stream()
            .anyMatch(point -> point.availability() == SupplyDataQuality.AUTHORITATIVE);
        if (!hasAuthoritative) {
            return SupplyDataQuality.UNAVAILABLE;
        }
        boolean hasInsufficientNonEmptyPoint = points.stream()
            .anyMatch(point -> point.sampleSize() > 0
                && point.availability() == SupplyDataQuality.UNAVAILABLE);
        return hasInsufficientNonEmptyPoint ? SupplyDataQuality.PARTIAL : SupplyDataQuality.AUTHORITATIVE;
    }

    private List<UnavailableReason> seriesReasons(List<PerformancePoint> points) {
        boolean insufficient = points.stream().anyMatch(point -> point.sampleSize() > 0
            && point.availability() == SupplyDataQuality.UNAVAILABLE);
        boolean anyAuthoritative = points.stream()
            .anyMatch(point -> point.availability() == SupplyDataQuality.AUTHORITATIVE);
        if (insufficient || !anyAuthoritative) {
            return List.of(insufficient());
        }
        return List.of();
    }

    private List<BigDecimal> sorted(List<BigDecimal> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        return requested.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private BigDecimal median(List<BigDecimal> sorted) {
        int size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2).setScale(2, RoundingMode.HALF_UP);
        }
        return sorted.get(size / 2 - 1)
            .add(sorted.get(size / 2))
            .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal nearestRank(List<BigDecimal> sorted, double percentile) {
        int rank = (int) Math.ceil(percentile * sorted.size());
        return sorted.get(Math.max(0, rank - 1)).setScale(2, RoundingMode.HALF_UP);
    }

    private LocalDate isoWeekStart(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
    }

    private String isoWeek(LocalDate weekStart) {
        return "%d-W%02d".formatted(
            weekStart.get(IsoFields.WEEK_BASED_YEAR),
            weekStart.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    private void increment(Map<String, Long> counts, String code) {
        counts.merge(code, 1L, Long::sum);
    }

    private UnavailableReason insufficient() {
        return reason(
            "INSUFFICIENT_COMPLETED_SAMPLE",
            "supply.performance.sample.insufficient");
    }

    private UnavailableReason reason(String code, String messageKey) {
        return new UnavailableReason(code, messageKey);
    }

    private BadRequestException bad(String code, String message) {
        return new BadRequestException(
            code + ": " + message,
            "supply.performance.request.invalid",
            code);
    }

    private record PerformanceRange(
        LocalDate from,
        LocalDate to,
        List<LocalDate> weekStarts
    ) {
        private boolean contains(LocalDate date) {
            return date != null && !date.isBefore(from) && date.isBefore(to);
        }
    }
}
