package com.enterprise.mm.service;

import static com.enterprise.mm.enums.ManagementOpportunityConfidence.HIGH;
import static com.enterprise.mm.enums.ManagementOpportunityDataCompleteness.COMPLETE;
import static com.enterprise.mm.enums.ManagementOpportunityDataCompleteness.DATA_INCOMPLETE;
import static com.enterprise.mm.enums.ManagementOpportunityStatus.APPROVED;
import static com.enterprise.mm.enums.ManagementOpportunityStatus.DRAFT;
import static com.enterprise.mm.enums.ManagementOpportunityType.PROCUREMENT_PRICE_VARIANCE;
import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.entity.ManagementOpportunity;
import com.enterprise.mm.payload.ManagementOpportunityBreakdownResponse;
import com.enterprise.mm.payload.ManagementOpportunitySummaryResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class ManagementOpportunityAnalyticsTest {
    private static final Instant GENERATED_AT = Instant.parse("2026-07-15T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(GENERATED_AT, ZoneOffset.UTC);

    @Test
    void keepsCurrencyBucketsSeparateAndDeduplicatesApprovedSnapshots() {
        UUID repeatedSource = UUID.randomUUID();
        UUID repeatedLine = UUID.randomUUID();
        ManagementOpportunity oldSnapshot = opportunity(
            repeatedSource, repeatedLine, APPROVED.name(), COMPLETE,
            "UZS", "UZS", "100", "100", LocalDate.of(2026, 6, 1)
        );
        ManagementOpportunity latestSnapshot = opportunity(
            repeatedSource, repeatedLine, APPROVED.name(), COMPLETE,
            "UZS", "UZS", "125", "125", LocalDate.of(2026, 7, 10)
        );
        ManagementOpportunity converted = opportunity(
            UUID.randomUUID(), UUID.randomUUID(), APPROVED.name(), COMPLETE,
            "EUR", "USD", "36", "40", LocalDate.of(2026, 7, 8)
        );
        converted.setExchangeRate(new BigDecimal("1.11111111"));
        converted.setExchangeRateDate(LocalDate.of(2026, 7, 8));
        converted.setExchangeRateSource("CBU");
        ManagementOpportunity incomplete = opportunity(
            UUID.randomUUID(), UUID.randomUUID(), DRAFT.name(), DATA_INCOMPLETE,
            "EUR", null, "15", null, LocalDate.of(2026, 5, 1)
        );

        ManagementOpportunityAnalytics analytics = new ManagementOpportunityAnalytics(CLOCK);
        ManagementOpportunitySummaryResponse result = analytics.summary(
            List.of(oldSnapshot, latestSnapshot, converted, incomplete)
        );

        assertThat(result.generatedAt()).isEqualTo(GENERATED_AT);
        assertThat(result.approvedCaseCount()).isEqualTo(2);
        assertThat(result.approvedNormalizedAmount()).isNull();
        assertThat(result.approvedCurrencyCode()).isNull();
        assertThat(result.mixedApprovedCurrencies()).isTrue();
        assertThat(result.approvedCurrencyBuckets())
            .extracting(
                ManagementOpportunitySummaryResponse.CurrencyAmount::currencyCode,
                ManagementOpportunitySummaryResponse.CurrencyAmount::amount,
                ManagementOpportunitySummaryResponse.CurrencyAmount::caseCount
            )
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("UZS", new BigDecimal("125"), 1L),
                org.assertj.core.groups.Tuple.tuple("USD", new BigDecimal("40"), 1L)
            );
        assertThat(result.dataIncompleteCount()).isEqualTo(1);
        assertThat(result.currencyConversionMissingCount()).isEqualTo(1);
        assertThat(result.conversion().aggregationBasis()).isEqualTo("STORED_CASE_NORMALIZATION");
        assertThat(result.conversion().convertedCaseCount()).isEqualTo(1);
        assertThat(result.conversion().rateSources()).containsExactly("CBU");
        assertThat(result.waterfallByCurrency())
            .allSatisfy(series -> {
                assertThat(series.realizedAmount()).isNull();
                assertThat(series.realizedAvailable()).isFalse();
            });
        assertThat(result.aging()).extracting(ManagementOpportunitySummaryResponse.AgingBucket::bucket)
            .containsExactly("0_7_DAYS", "8_30_DAYS", "31_60_DAYS", "61_PLUS_DAYS");
    }

    @Test
    void breakdownPreservesGroupCurrencyAndExactCaseLinks() {
        ManagementOpportunity uzs = opportunity(
            UUID.randomUUID(), UUID.randomUUID(), APPROVED.name(), COMPLETE,
            "UZS", "UZS", "125", "125", LocalDate.of(2026, 7, 10)
        );
        ManagementOpportunity usd = opportunity(
            UUID.randomUUID(), UUID.randomUUID(), APPROVED.name(), COMPLETE,
            "USD", "USD", "40", "40", LocalDate.of(2026, 7, 8)
        );

        ManagementOpportunityBreakdownResponse result = new ManagementOpportunityAnalytics(CLOCK).breakdown(
            List.of(uzs, usd),
            Map.of(uzs.getId(), List.of(UUID.randomUUID())),
            PageRequest.of(0, 20)
        );

        assertThat(result.byType())
            .extracting(
                ManagementOpportunityBreakdownResponse.GroupAmount::group,
                ManagementOpportunityBreakdownResponse.GroupAmount::currencyCode,
                ManagementOpportunityBreakdownResponse.GroupAmount::normalizedAmount
            )
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(PROCUREMENT_PRICE_VARIANCE.name(), "UZS", new BigDecimal("125")),
                org.assertj.core.groups.Tuple.tuple(PROCUREMENT_PRICE_VARIANCE.name(), "USD", new BigDecimal("40"))
            );
        assertThat(result.cases()).allSatisfy(item -> {
            assertThat(item.group()).isEqualTo(PROCUREMENT_PRICE_VARIANCE.name());
            assertThat(item.drilldownUrl()).isEqualTo(
                "/app/management-opportunities?tab=cases&caseId=" + item.id()
            );
        });
    }

    private ManagementOpportunity opportunity(
        UUID sourceId,
        UUID lineId,
        String status,
        com.enterprise.mm.enums.ManagementOpportunityDataCompleteness completeness,
        String sourceCurrency,
        String normalizedCurrency,
        String sourceAmount,
        String normalizedAmount,
        LocalDate asOfDate
    ) {
        ManagementOpportunity entity = new ManagementOpportunity();
        entity.setId(UUID.randomUUID());
        entity.setTenantId("tenant-mo-report");
        entity.setCompanyId(UUID.randomUUID());
        entity.setCode("MO-" + entity.getId());
        entity.setName("Management opportunity");
        entity.setStatus(status);
        entity.setOpportunityType(PROCUREMENT_PRICE_VARIANCE);
        entity.setCalculationRunId(UUID.randomUUID());
        entity.setSourceAggregateType("PurchaseOrder");
        entity.setSourceAggregateId(sourceId);
        entity.setSourceLineId(lineId);
        entity.setAsOfDate(asOfDate);
        entity.setSourceCurrencyCode(sourceCurrency);
        entity.setNormalizedCurrencyCode(normalizedCurrency);
        entity.setSourceAmount(new BigDecimal(sourceAmount));
        entity.setNormalizedAmount(normalizedAmount == null ? null : new BigDecimal(normalizedAmount));
        entity.setNormalizationQuality(normalizedCurrency == null ? "DATA_INCOMPLETE" : "SAME_CURRENCY");
        entity.setFormulaCode("TEST");
        entity.setFormulaSnapshot("{}");
        entity.setConfidence(HIGH);
        entity.setDataCompleteness(completeness);
        entity.setCreatedAt(LocalDateTime.of(asOfDate, java.time.LocalTime.NOON));
        return entity;
    }
}
