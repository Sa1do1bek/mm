package com.enterprise.mm.overview;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.ConcentrationCell;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.DecisionRow;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.Health;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.LeadTimeBucket;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.MonthTrend;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.SourceHealth;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.SourceStatus;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.SupplierExposure;
import com.enterprise.mm.overview.service.ManagementOverviewDataSource;
import com.enterprise.mm.overview.service.ManagementOverviewDataSource.ManagementFacts;
import com.enterprise.mm.overview.service.ManagementOverviewService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ManagementOverviewServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-15T08:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @AfterEach
    void clearCompany() {
        CompanyContext.clear();
    }

    @Test
    void calculatesInspectibleOutcomesAndWorkingCapitalWithoutClamping() {
        UUID companyId = UUID.fromString("20000000-0000-0000-0000-000000000002");
        CompanyContext.setCompany(companyId);
        ManagementOverviewDataSource source = (from, to, currency) -> new ManagementFacts(
            "UZS",
            null,
            null,
            new BigDecimal("1000"),
            new BigDecimal("100"),
            new BigDecimal("250"),
            8L,
            2L,
            10L,
            7L,
            List.of(new MonthTrend(YearMonth.of(2026, 7), new BigDecimal("500"),
                new BigDecimal("350"), 2, null, null, null)),
            List.of(new ConcentrationCell(
                UUID.randomUUID(), "Главный склад", UUID.randomUUID(), "Металлы",
                new BigDecimal("500"), "UZS", new BigDecimal("50"),
                "/app/stock-balances?warehouseId=warehouse-1")),
            List.of(new SupplierExposure(
                UUID.randomUUID(), "SUP-1", "Поставщик", new BigDecimal("4.5"),
                new BigDecimal("125"), "UZS", new BigDecimal("90"), 2, 2,
                false, "/app/suppliers?tab=governance&supplierId=supplier-1")),
            List.<LeadTimeBucket>of(),
            List.of(new DecisionRow(
                "decision-1", "Решение", "WARNING", new BigDecimal("25"), "UZS",
                "MM_MANAGER", LocalDate.of(2026, 7, 16), "Проверить",
                "/app/integration-health?queue=MANUAL_DECISION", "OPEN")),
            List.of(new SourceHealth("inventory", "Запасы", SourceStatus.HEALTHY,
                null, NOW, "/app/valuation"))
        );

        var result = new ManagementOverviewService(source, CLOCK).overview(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 15), "UZS");

        assertThat(result.metadata().companyId()).isEqualTo(companyId);
        assertThat(result.metadata().displayCurrency()).isEqualTo("UZS");
        assertThat(result.datasets()).extracting(item -> item.key(), item -> item.dataBasis())
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("procurement", "SELECTED_PERIOD"),
                org.assertj.core.groups.Tuple.tuple("inventory", "CURRENT_SNAPSHOT"),
                org.assertj.core.groups.Tuple.tuple("supplierExposure", "CURRENT_SNAPSHOT")
            );
        assertThat(result.datasets().get(0).periodFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(result.datasets().get(0).periodTo()).isEqualTo(LocalDate.of(2026, 7, 15));
        assertThat(result.datasets().get(0).asOf()).isNull();
        assertThat(result.datasets().get(1).periodFrom()).isNull();
        assertThat(result.datasets().get(1).periodTo()).isNull();
        assertThat(result.datasets().get(1).asOf()).isEqualTo(NOW);
        assertThat(result.outcomes()).extracting(item -> item.key())
            .containsExactly("on_time_fulfilled_demand", "inventory_at_risk", "procurement_sla", "order_completion");
        assertThat(result.outcomes().get(0).health()).isEqualTo(Health.UNAVAILABLE);
        assertThat(result.outcomes().get(1).value()).isEqualByComparingTo("250");
        assertThat(result.outcomes().get(2).value()).isEqualByComparingTo("75.00");
        assertThat(result.outcomes().get(3).value()).isEqualByComparingTo("70.00");
        java.net.URI procurementSla = java.net.URI.create(result.outcomes().get(2).drilldownUrl());
        assertThat(procurementSla.getQuery()).contains(
            "tab=registry",
            "companyScope=CURRENT_COMPANY",
            "orderDateFrom=2026-01-01",
            "orderDateTo=2026-07-15",
            "expectedDeliveryDateFrom=0001-01-01",
            "currencyCode=UZS",
            "status=DRAFT",
            "status=PARTIALLY_RECEIVED"
        );
        assertThat(procurementSla.getQuery()).doesNotContain("status=REJECTED", "status=RETURNED", "dateFrom=");
        java.net.URI orderCompletion = java.net.URI.create(result.outcomes().get(3).drilldownUrl());
        assertThat(orderCompletion.getQuery()).contains(
            "companyScope=CURRENT_COMPANY",
            "orderDateFrom=2026-01-01",
            "orderDateTo=2026-07-15",
            "currencyCode=UZS",
            "status=RECEIVED",
            "status=CLOSED",
            "status=REJECTED",
            "status=RETURNED"
        );
        assertThat(result.outcomes().get(1).drilldownUrl()).contains(
            "mode=cases",
            "status=DETECTED,CASE_CREATED,UNDER_REVIEW,REVIEW_DEFERRED,COMMISSION_REVIEW,"
                + "APPROVAL_PENDING,DECISION_APPROVED,IN_EXECUTION,EXECUTED",
            "companyScope=CURRENT_COMPANY"
        );
        MonthTrend july = result.trends().getFirst();
        assertThat(july.orderedDrilldownUrl()).contains(
            "orderDateFrom=2026-07-01",
            "orderDateTo=2026-07-15",
            "currencyCode=UZS",
            "status=DRAFT",
            "status=CLOSED",
            "companyScope=CURRENT_COMPANY"
        ).doesNotContain("status=CANCELLED");
        assertThat(july.completedDrilldownUrl()).contains(
            "orderDateFrom=2026-07-01",
            "orderDateTo=2026-07-15",
            "status=RECEIVED",
            "status=CLOSED",
            "companyScope=CURRENT_COMPANY"
        );
        assertThat(july.overdueDrilldownUrl()).contains(
            "orderDateFrom=2026-07-01",
            "orderDateTo=2026-07-15",
            "expectedDeliveryDateFrom=0001-01-01",
            "expectedDeliveryDateTo=2026-07-14",
            "status=DRAFT",
            "status=PARTIALLY_RECEIVED",
            "companyScope=CURRENT_COMPANY"
        );
        assertThat(result.workingCapital()).extracting(item -> item.key(), item -> item.value(), item -> item.kind())
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("GROSS_INVENTORY", new BigDecimal("1000"), "START"),
                org.assertj.core.groups.Tuple.tuple("IMPAIRMENT_RESERVE", new BigDecimal("-100"), "DECREASE"),
                org.assertj.core.groups.Tuple.tuple("NET_INVENTORY", new BigDecimal("900"), "TOTAL"),
                org.assertj.core.groups.Tuple.tuple("FROZEN_EXPOSURE", new BigDecimal("250"), "REFERENCE")
            );
        assertThat(result.outcomes()).extracting(item -> item.drilldownUrl())
            .allMatch(ManagementOverviewServiceTest::usesCurrentCompanyScope);
        assertThat(result.workingCapital()).extracting(item -> item.drilldownUrl())
            .allMatch(ManagementOverviewServiceTest::usesCurrentCompanyScope);
        assertThat(result.workingCapital().getLast().drilldownUrl()).contains(
            "mode=cases",
            "status=DETECTED,CASE_CREATED,UNDER_REVIEW,REVIEW_DEFERRED,COMMISSION_REVIEW,"
                + "APPROVAL_PENDING,DECISION_APPROVED,IN_EXECUTION,EXECUTED"
        );
        assertThat(result.concentration()).extracting(ConcentrationCell::drilldownUrl)
            .allMatch(ManagementOverviewServiceTest::usesCurrentCompanyScope);
        assertThat(result.supplierExposure()).extracting(SupplierExposure::drilldownUrl)
            .allMatch(ManagementOverviewServiceTest::usesCurrentCompanyScope);
        assertThat(result.decisions()).extracting(DecisionRow::drilldownUrl)
            .allMatch(ManagementOverviewServiceTest::usesCurrentCompanyScope);
        assertThat(result.sourceHealth()).extracting(SourceHealth::drilldownUrl)
            .allMatch(ManagementOverviewServiceTest::usesCurrentCompanyScope);
    }

    @Test
    void marksCurrencyDependentValuesUnavailableWhenConversionWasNotPerformed() {
        ManagementOverviewDataSource source = (from, to, currency) -> new ManagementFacts(
            "USD",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0L,
            0L,
            List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(new SourceHealth("inventory", "Запасы", SourceStatus.UNAVAILABLE,
                "Нет подтверждённого курса UZS/USD", null, "/app/valuation"))
        );

        var result = new ManagementOverviewService(source, CLOCK).overview(null, null, "usd");

        assertThat(result.metadata().status()).isEqualTo(Health.PARTIAL);
        assertThat(result.metadata().messages()).contains("Нет подтверждённого курса UZS/USD");
        assertThat(result.outcomes().get(1).value()).isNull();
        assertThat(result.outcomes().get(1).health()).isEqualTo(Health.UNAVAILABLE);
        assertThat(result.workingCapital()).isEmpty();
    }

    @Test
    void removesReferenceCodesFromConcentrationLabels() {
        UUID companyId = UUID.fromString("20000000-0000-0000-0000-000000000002");
        CompanyContext.setCompany(companyId);
        ManagementOverviewDataSource source = (from, to, currency) -> new ManagementFacts(
            "UZS",
            null,
            null,
            new BigDecimal("1000"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            null,
            0L,
            0L,
            List.of(),
            List.of(new ConcentrationCell(
                UUID.randomUUID(), "WH-00008 · Sulfat kislota saqlash ombori",
                UUID.randomUUID(), "MGR-00001 · Kimyoviy xomashyo",
                new BigDecimal("500"), "UZS", new BigDecimal("50"),
                "/app/stock-balances?warehouseId=warehouse-1&materialGroupId=group-1")),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );

        var result = new ManagementOverviewService(source, CLOCK).overview(null, null, "UZS");

        assertThat(result.concentration()).extracting(
            ConcentrationCell::warehouseLabel,
            ConcentrationCell::materialGroupLabel
        ).containsExactly(org.assertj.core.groups.Tuple.tuple(
            "Sulfat kislota saqlash ombori",
            "Kimyoviy xomashyo"
        ));
    }

    @Test
    void doesNotAdvertisePriorPeriodComparisonsThatWereNotCalculated() {
        assertThat(java.util.Arrays.stream(
            com.enterprise.mm.overview.payload.ManagementOverviewDtos.Metadata.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName))
            .doesNotContain("comparisonFrom", "comparisonTo");
        assertThat(java.util.Arrays.stream(
            com.enterprise.mm.overview.payload.ManagementOverviewDtos.Outcome.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName))
            .doesNotContain("comparisonValue", "comparisonDelta");
    }

    private static boolean usesCurrentCompanyScope(String url) {
        return url != null && java.net.URI.create(url).getQuery() != null
            && java.net.URI.create(url).getQuery().contains("companyScope=CURRENT_COMPANY");
    }
}
