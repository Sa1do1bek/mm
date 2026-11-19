package com.enterprise.mm.supply.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.supply.workspace.domain.SupplyWorkspaceQuery;
import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentSort;
import com.enterprise.mm.supply.workspace.enums.SupplyDataQuality;
import com.enterprise.mm.supply.workspace.enums.SupplyLens;
import com.enterprise.mm.supply.workspace.enums.SupplyPerformanceMetricKey;
import com.enterprise.mm.supply.workspace.enums.SupplyQueueKey;
import com.enterprise.mm.supply.workspace.enums.SupplyRiskKey;
import com.enterprise.mm.supply.workspace.enums.SupplySourceState;
import com.enterprise.mm.supply.workspace.payload.SupplyPerformanceDtos.PerformancePoint;
import com.enterprise.mm.supply.workspace.payload.SupplyPerformanceDtos.PerformanceSeries;
import com.enterprise.mm.supply.workspace.payload.SupplyPerformanceDtos.SupplyPerformanceResponse;
import com.enterprise.mm.supply.workspace.repository.SupplyPerformanceQueryRepository;
import com.enterprise.mm.supply.workspace.repository.SupplyPerformanceQueryRepository.DurationObservation;
import com.enterprise.mm.supply.workspace.repository.SupplyPerformanceQueryRepository.LandedCostObservation;
import com.enterprise.mm.supply.workspace.service.SupplyPerformanceService;
import com.enterprise.mm.supply.workspace.service.SupplyWorkspaceAccessPolicy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SupplyPerformanceServiceTest {
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000000111");
    private static final Instant NOW = Instant.parse("2026-07-12T07:00:00Z");
    private static final LocalDate DEFAULT_FROM = LocalDate.of(2026, 4, 13);
    private static final LocalDate DEFAULT_TO = LocalDate.of(2026, 7, 13);
    private static final LocalDate SAMPLE_WEEK = LocalDate.of(2026, 7, 6);

    private SupplyPerformanceQueryRepository repository;
    private SupplyWorkspaceAccessPolicy accessPolicy;
    private SupplyPerformanceService service;

    @BeforeEach
    void setUp() {
        repository = mock(SupplyPerformanceQueryRepository.class);
        accessPolicy = mock(SupplyWorkspaceAccessPolicy.class);
        when(accessPolicy.bindAuthenticatedIdentity(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.asnConfirmationObservations(any())).thenReturn(List.of());
        when(repository.exceptionResolutionObservations(any())).thenReturn(List.of());
        when(repository.landedCostUpliftObservations(any())).thenReturn(List.of());
        service = new SupplyPerformanceService(
            repository, accessPolicy, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void defaultsToThirteenOrderedIsoWeeksAndReturnsTheFrozenFourSeriesContract() {
        SupplyPerformanceResponse response = service.performance(query(null, null));

        assertThat(response.appliedScope().tenantId()).isEqualTo("tenant-supply-performance");
        assertThat(response.appliedScope().companyIds()).containsExactly(COMPANY_ID);
        assertThat(response.appliedFilters().from()).isEqualTo(DEFAULT_FROM);
        assertThat(response.appliedFilters().to()).isEqualTo(DEFAULT_TO);
        assertThat(response.series()).extracting(PerformanceSeries::key).containsExactly(
            SupplyPerformanceMetricKey.ASN_CONFIRMATION,
            SupplyPerformanceMetricKey.EXCEPTION_RESOLUTION,
            SupplyPerformanceMetricKey.LANDED_COST_UPLIFT,
            SupplyPerformanceMetricKey.OTIF);
        assertThat(response.series().subList(0, 3)).allSatisfy(series -> {
            assertThat(series.points()).hasSize(13);
            assertThat(series.points()).extracting(PerformancePoint::weekStart)
                .isSorted().startsWith(DEFAULT_FROM).endsWith(SAMPLE_WEEK);
            assertThat(series.points()).extracting(PerformancePoint::isoWeek)
                .startsWith("2026-W16").endsWith("2026-W28");
            assertThat(series.points()).allSatisfy(point -> assertThat(point.drilldownTarget()).isNull());
        });
        assertThat(series(response, SupplyPerformanceMetricKey.OTIF)).satisfies(series -> {
            assertThat(series.availability()).isEqualTo(SupplyDataQuality.UNAVAILABLE);
            assertThat(series.points()).isEmpty();
            assertThat(series.denominator()).isNull();
            assertThat(series.reasons()).extracting(reason -> reason.code())
                .containsExactly("RECEIPT_ALLOCATION_INCOMPLETE");
        });
        assertThat(response.sourceFreshness()).extracting(source -> source.source())
            .containsExactly("ADVANCE_SHIPPING_NOTICE", "LANDED_LOGISTICS_EVENT", "IMPORT_DELIVERY", "OTIF");
        assertThat(response.sourceFreshness().getLast().state()).isEqualTo(SupplySourceState.UNAVAILABLE);
        assertThat(response.partialErrors()).isEmpty();
        assertThat(response.generatedAt()).isEqualTo(NOW);
        assertThat(response.timeZone()).isEqualTo("Asia/Tashkent");
        assertThat(response.definitionVersion()).isEqualTo("supply-workspace.v1");
    }

    @Test
    void calculatesDeterministicAsnMedianAndNearestRankP90FromSentToConfirmed() {
        when(repository.asnConfirmationObservations(any())).thenReturn(List.of(
            duration("1", SAMPLE_WEEK, 1),
            duration("2", SAMPLE_WEEK, 2),
            duration("3", SAMPLE_WEEK, 3),
            duration("4", SAMPLE_WEEK, 4),
            duration("5", SAMPLE_WEEK, 5),
            duration("6", SAMPLE_WEEK, 10)));

        SupplyPerformanceResponse response = service.performance(query(DEFAULT_FROM, DEFAULT_TO));

        PerformanceSeries series = series(response, SupplyPerformanceMetricKey.ASN_CONFIRMATION);
        PerformancePoint point = point(series, SAMPLE_WEEK);
        assertThat(point.sampleSize()).isEqualTo(6);
        assertThat(point.median()).isEqualByComparingTo("3.50");
        assertThat(point.p90()).isEqualByComparingTo("10.00");
        assertThat(point.value()).isNull();
        assertThat(point.availability()).isEqualTo(SupplyDataQuality.AUTHORITATIVE);
        assertThat(series.denominator().eligibleCount()).isEqualTo(6);
        assertThat(series.denominator().excludedCount()).isZero();
        assertThat(series.denominator().minimumSampleSize()).isEqualTo(5);
        assertThat(series.denominator().cohortDateBasis()).isEqualTo("CONFIRMED_AT");
    }

    @Test
    void withholdsPercentilesWhenAWeeklyCompletedSampleHasFewerThanFiveObservations() {
        when(repository.asnConfirmationObservations(any())).thenReturn(List.of(
            duration("1", SAMPLE_WEEK, 1),
            duration("2", SAMPLE_WEEK, 2),
            duration("3", SAMPLE_WEEK, 3),
            duration("4", SAMPLE_WEEK, 4)));

        PerformanceSeries series = series(
            service.performance(query(DEFAULT_FROM, DEFAULT_TO)),
            SupplyPerformanceMetricKey.ASN_CONFIRMATION);
        PerformancePoint point = point(series, SAMPLE_WEEK);

        assertThat(point.sampleSize()).isEqualTo(4);
        assertThat(point.median()).isNull();
        assertThat(point.p90()).isNull();
        assertThat(point.availability()).isEqualTo(SupplyDataQuality.UNAVAILABLE);
        assertThat(point.reasons()).extracting(reason -> reason.code())
            .containsExactly("INSUFFICIENT_COMPLETED_SAMPLE");
        assertThat(series.availability()).isEqualTo(SupplyDataQuality.UNAVAILABLE);
    }

    @Test
    void excludesUnresolvedAndInvalidExceptionsFromCompletionPercentiles() {
        LocalDateTime start = SAMPLE_WEEK.atTime(8, 0);
        when(repository.exceptionResolutionObservations(any())).thenReturn(List.of(
            observation("1", start, start.plusHours(2)),
            observation("2", start, start.plusHours(4)),
            observation("3", start, start.plusHours(6)),
            observation("4", start, start.plusHours(8)),
            observation("5", start, start.plusHours(10)),
            observation("unresolved", start, null),
            observation("negative", start.plusHours(3), start.plusHours(1))));

        PerformanceSeries series = series(
            service.performance(query(DEFAULT_FROM, DEFAULT_TO)),
            SupplyPerformanceMetricKey.EXCEPTION_RESOLUTION);
        PerformancePoint point = point(series, SAMPLE_WEEK);

        assertThat(point.sampleSize()).isEqualTo(5);
        assertThat(point.median()).isEqualByComparingTo("6.00");
        assertThat(point.p90()).isEqualByComparingTo("10.00");
        assertThat(series.denominator().eligibleCount()).isEqualTo(5);
        assertThat(series.denominator().excludedCount()).isEqualTo(2);
        assertThat(series.denominator().excludedByReason()).containsExactlyInAnyOrderEntriesOf(Map.of(
            "UNRESOLVED", 1L,
            "NEGATIVE_DURATION", 1L));
    }

    @Test
    void calculatesLandedCostUpliftAndExcludesZeroBaseAndCurrencyInconsistentEvidence() {
        when(repository.landedCostUpliftObservations(any())).thenReturn(List.of(
            uplift("1", "100", "110", true, true),
            uplift("2", "100", "120", true, true),
            uplift("3", "100", "130", true, true),
            uplift("4", "100", "140", true, true),
            uplift("5", "100", "150", true, true),
            uplift("zero", "0", "50", true, true),
            uplift("currency", "100", "130", false, true),
            uplift("unposted", "100", "130", true, false)));

        PerformanceSeries series = series(
            service.performance(query(DEFAULT_FROM, DEFAULT_TO)),
            SupplyPerformanceMetricKey.LANDED_COST_UPLIFT);
        PerformancePoint point = point(series, SAMPLE_WEEK);

        assertThat(point.sampleSize()).isEqualTo(5);
        assertThat(point.value()).isEqualByComparingTo("30.00");
        assertThat(point.median()).isNull();
        assertThat(point.p90()).isNull();
        assertThat(point.availability()).isEqualTo(SupplyDataQuality.AUTHORITATIVE);
        assertThat(series.unit()).isEqualTo("PERCENT");
        assertThat(series.denominator().eligibleCount()).isEqualTo(5);
        assertThat(series.denominator().excludedCount()).isEqualTo(3);
        assertThat(series.denominator().excludedByReason()).containsExactlyInAnyOrderEntriesOf(Map.of(
            "ZERO_BASE_GOODS_AMOUNT", 1L,
            "CURRENCY_INCONSISTENT", 1L,
            "UNPOSTED_COST_COMPONENT", 1L));
    }

    @Test
    void isolatesOneFailedPerformanceSourceWithoutReplacingItWithZero() {
        when(repository.exceptionResolutionObservations(any()))
            .thenThrow(new IllegalStateException("exception source unavailable"));
        when(repository.asnConfirmationObservations(any())).thenReturn(List.of(
            duration("1", SAMPLE_WEEK, 1), duration("2", SAMPLE_WEEK, 2),
            duration("3", SAMPLE_WEEK, 3), duration("4", SAMPLE_WEEK, 4),
            duration("5", SAMPLE_WEEK, 5)));

        SupplyPerformanceResponse response = service.performance(query(DEFAULT_FROM, DEFAULT_TO));

        assertThat(series(response, SupplyPerformanceMetricKey.ASN_CONFIRMATION).availability())
            .isEqualTo(SupplyDataQuality.AUTHORITATIVE);
        assertThat(series(response, SupplyPerformanceMetricKey.EXCEPTION_RESOLUTION)).satisfies(series -> {
            assertThat(series.availability()).isEqualTo(SupplyDataQuality.UNAVAILABLE);
            assertThat(series.points()).isEmpty();
            assertThat(series.denominator()).isNull();
            assertThat(series.reasons()).extracting(reason -> reason.code())
                .containsExactly("PERFORMANCE_SOURCE_FAILED");
        });
        assertThat(response.partialErrors()).singleElement().satisfies(error -> {
            assertThat(error.source()).isEqualTo("LANDED_LOGISTICS_EVENT");
            assertThat(error.code()).isEqualTo("PERFORMANCE_SOURCE_FAILED");
            assertThat(error.retryable()).isTrue();
        });
        assertThat(response.sourceFreshness())
            .filteredOn(source -> source.source().equals("LANDED_LOGISTICS_EVENT"))
            .singleElement().satisfies(source -> {
                assertThat(source.state()).isEqualTo(SupplySourceState.FAILED);
                assertThat(source.lastSuccessfulAt()).isNull();
            });
    }

    @Test
    void rejectsRangesOverNinetyThreeDaysOrMoreThanThirteenIsoWeekBuckets() {
        assertThatThrownBy(() -> service.performance(query(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 5))))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("SUPPLY_PERFORMANCE_RANGE_TOO_LARGE");

        assertThatThrownBy(() -> service.performance(query(
            LocalDate.of(2026, 4, 14), LocalDate.of(2026, 7, 14))))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("SUPPLY_PERFORMANCE_TOO_MANY_ISO_WEEKS");
    }

    @Test
    void declaresMyWorkPerformanceUnavailableInsteadOfIgnoringTheActiveLens() {
        SupplyWorkspaceQuery team = query(DEFAULT_FROM, DEFAULT_TO);
        SupplyWorkspaceQuery myWork = new SupplyWorkspaceQuery(
            team.tenantId(), team.authorizedCompanyIds(), team.authorizedWarehouseIds(),
            team.warehouseScoped(), team.asOf(), team.asOfDate(), team.timeZone(),
            team.from(), team.to(), SupplyLens.MY_WORK, null, team.companyId(),
            team.supplierId(), team.purchaseOrderId(), team.contractId(), team.warehouseId(),
            team.transportMode(), team.currencyCode(), team.status(), team.risk(), team.queue(),
            team.search(), team.sort(), UUID.randomUUID(), "supply-specialist");

        SupplyPerformanceResponse response = service.performance(myWork);

        assertThat(response.series().subList(0, 3)).allSatisfy(series -> {
            assertThat(series.availability()).isEqualTo(SupplyDataQuality.UNAVAILABLE);
            assertThat(series.points()).isEmpty();
            assertThat(series.denominator()).isNull();
            assertThat(series.reasons()).extracting(reason -> reason.code())
                .containsExactly("MY_WORK_FILTER_UNSUPPORTED");
        });
        assertThat(response.sourceFreshness().subList(0, 3)).allSatisfy(source ->
            assertThat(source.state()).isEqualTo(SupplySourceState.UNAVAILABLE));
        assertThat(response.partialErrors()).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void declaresOwnerRiskAndQueueFiltersUnsupportedInsteadOfSilentlyDroppingThem() {
        SupplyWorkspaceQuery team = query(DEFAULT_FROM, DEFAULT_TO);
        SupplyWorkspaceQuery filtered = new SupplyWorkspaceQuery(
            team.tenantId(), team.authorizedCompanyIds(), team.authorizedWarehouseIds(),
            team.warehouseScoped(), team.asOf(), team.asOfDate(), team.timeZone(),
            team.from(), team.to(), SupplyLens.TEAM, UUID.randomUUID(), team.companyId(),
            team.supplierId(), team.purchaseOrderId(), team.contractId(), team.warehouseId(),
            team.transportMode(), team.currencyCode(), team.status(),
            SupplyRiskKey.IMPORT_ETA_RISK, SupplyQueueKey.ACTIVE_IMPORT_ETA_RISK,
            team.search(), team.sort());

        SupplyPerformanceResponse response = service.performance(filtered);

        assertThat(response.series().subList(0, 3)).allSatisfy(series ->
            assertThat(series.reasons()).extracting(reason -> reason.code()).containsExactly(
                "OWNER_FILTER_UNSUPPORTED",
                "RISK_FILTER_UNSUPPORTED",
                "QUEUE_FILTER_UNSUPPORTED"));
        assertThat(response.series().subList(0, 3)).allSatisfy(series -> {
            assertThat(series.points()).isEmpty();
            assertThat(series.denominator()).isNull();
        });
        verifyNoInteractions(repository);
    }

    private DurationObservation duration(String suffix, LocalDate week, long hours) {
        LocalDateTime start = week.atTime(8, 0);
        return observation(suffix, start, start.plusHours(hours));
    }

    private DurationObservation observation(
        String suffix,
        LocalDateTime startedAt,
        LocalDateTime completedAt
    ) {
        return new DurationObservation(uuid(suffix), startedAt, completedAt);
    }

    private LandedCostObservation uplift(
        String suffix,
        String base,
        String landed,
        boolean currencyConsistent,
        boolean postedCostComplete
    ) {
        return new LandedCostObservation(
            uuid(suffix), SAMPLE_WEEK.plusDays(2), new BigDecimal(base), new BigDecimal(landed),
            "USD", currencyConsistent, postedCostComplete);
    }

    private UUID uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private PerformanceSeries series(
        SupplyPerformanceResponse response,
        SupplyPerformanceMetricKey key
    ) {
        return response.series().stream().filter(series -> series.key() == key).findFirst().orElseThrow();
    }

    private PerformancePoint point(PerformanceSeries series, LocalDate weekStart) {
        return series.points().stream()
            .filter(point -> point.weekStart().equals(weekStart))
            .findFirst().orElseThrow();
    }

    private SupplyWorkspaceQuery query(LocalDate from, LocalDate to) {
        return new SupplyWorkspaceQuery(
            "tenant-supply-performance", Set.of(COMPANY_ID), Set.of(), false,
            NOW, LocalDate.of(2026, 7, 12), ZoneId.of("Asia/Tashkent"),
            from, to, SupplyLens.TEAM, null, COMPANY_ID, null, null, null, null,
            null, null, null, null, null, null, SupplyCommitmentSort.DEFAULT);
    }
}
