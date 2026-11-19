package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.payload.ReportFilter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportMetricDefinitionTest {

    @Test
    void abcUsesCumulativeShareBeforeCurrentItemForTheBoundary() {
        UUID dominant = UUID.randomUUID();
        UUID tail = UUID.randomUUID();

        var rows = ReportService.classifyAbc(Map.of(
            dominant, new BigDecimal("969"),
            tail, new BigDecimal("31")
        ));

        assertThat(rows).extracting(ReportService.AbcClassification::materialId)
            .containsExactly(dominant, tail);
        assertThat(rows.getFirst().category()).isEqualTo("A");
        assertThat(rows.getFirst().cumulativeShare()).isEqualByComparingTo("0.9690");
        assertThat(rows.getLast().category()).isEqualTo("C");
    }

    @Test
    void abcLeavesZeroValueCohortUnclassified() {
        var rows = ReportService.classifyAbc(Map.of(
            UUID.randomUUID(), BigDecimal.ZERO,
            UUID.randomUUID(), BigDecimal.ZERO
        ));

        assertThat(rows).allSatisfy(row -> {
            assertThat(row.category()).isEqualTo("UNCLASSIFIED");
            assertThat(row.cumulativeShare()).isEqualByComparingTo("0.0000");
        });
    }

    @Test
    void turnoverIsUnavailableWhenCurrentAvailableQuantityIsZero() {
        assertThat(ReportService.calculateTurnoverRatio(BigDecimal.TEN, BigDecimal.ZERO)).isNull();
        assertThat(ReportService.calculateTurnoverRatio(BigDecimal.TEN, new BigDecimal("2")))
            .isEqualByComparingTo("5.0000");
    }

    @Test
    void xyzUsesExactlyTwelveCalendarBucketsIncludingMonthsWithNoIssue() {
        YearMonth periodEnd = YearMonth.of(2026, 7);
        Map<YearMonth, BigDecimal> issues = new LinkedHashMap<>();
        issues.put(YearMonth.of(2025, 8), new BigDecimal("120"));

        var metrics = ReportService.calculateXyzMetrics(issues, periodEnd);

        assertThat(metrics.averageMonthlyIssue()).isEqualByComparingTo("10.0000");
        assertThat(metrics.variationCoefficient()).isGreaterThan(new BigDecimal("3"));
        assertThat(metrics.category()).isEqualTo("Z");
    }

    @Test
    void dashboardCountAndDrilldownShareOneOpenStatusContract() {
        assertThat(ReportService.openPurchaseRequestStatuses()).containsExactly(
            PurchaseRequestStatus.DRAFT.name(),
            PurchaseRequestStatus.APPROVAL_PENDING.name(),
            PurchaseRequestStatus.APPROVED.name()
        );
        assertThat(ReportService.openPurchaseOrderStatuses()).containsExactly(
            PurchaseOrderStatus.DRAFT.name(),
            PurchaseOrderStatus.APPROVAL_PENDING.name(),
            PurchaseOrderStatus.APPROVED.name(),
            PurchaseOrderStatus.SENT.name(),
            PurchaseOrderStatus.PARTIALLY_RECEIVED.name()
        );
    }

    @Test
    void savingsUsesPurchaseRequestBaselineOnceForSplitOrders() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 5, 10, 10, 0);
        PurchaseRequest request = request(new BigDecimal("100"), "UZS", createdAt);
        PurchaseOrder first = order(request.getId(), new BigDecimal("40"), "UZS", PurchaseOrderStatus.SENT, LocalDate.of(2026, 5, 15));
        PurchaseOrder second = order(request.getId(), new BigDecimal("50"), "UZS", PurchaseOrderStatus.RECEIVED, LocalDate.of(2026, 5, 20));

        Map<String, BigDecimal> savings = ReportService.calculateSavingsByCurrency(
            List.of(request),
            List.of(first, second),
            new ReportFilter(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), null)
        );

        assertThat(savings)
            .containsOnlyKeys("UZS")
            .containsEntry("UZS", new BigDecimal("10"));
    }

    @Test
    void savingsExcludesCancelledDraftAndCurrencyIncomparableCohorts() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 5, 10, 10, 0);
        PurchaseRequest comparable = request(new BigDecimal("100"), "UZS", createdAt);
        PurchaseRequest mixedCurrency = request(new BigDecimal("100"), "UZS", createdAt);

        List<PurchaseOrder> orders = List.of(
            order(comparable.getId(), new BigDecimal("60"), "UZS", PurchaseOrderStatus.SENT, LocalDate.of(2026, 5, 15)),
            order(comparable.getId(), new BigDecimal("1"), "UZS", PurchaseOrderStatus.CANCELLED, LocalDate.of(2026, 5, 16)),
            order(comparable.getId(), new BigDecimal("1"), "UZS", PurchaseOrderStatus.DRAFT, LocalDate.of(2026, 5, 17)),
            order(mixedCurrency.getId(), new BigDecimal("70"), "USD", PurchaseOrderStatus.SENT, LocalDate.of(2026, 5, 18))
        );

        Map<String, BigDecimal> savings = ReportService.calculateSavingsByCurrency(
            List.of(comparable, mixedCurrency),
            orders,
            new ReportFilter(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), null)
        );

        assertThat(savings)
            .containsOnlyKeys("UZS")
            .containsEntry("UZS", new BigDecimal("40"));
    }

    @Test
    void reportFilterNormalizesCurrencyAndRejectsInvalidRanges() {
        ReportFilter filter = new ReportFilter(
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 6, 30),
            " usd "
        );

        assertThat(filter.displayCurrency()).isEqualTo("USD");
        assertThatThrownBy(() -> new ReportFilter(
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 6, 30),
            null
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("from");
        assertThatThrownBy(() -> new ReportFilter(
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 6, 30),
            "dollar"
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("currency");
        assertThatThrownBy(() -> new ReportFilter(
            LocalDate.of(2020, 1, 1),
            LocalDate.of(2026, 6, 30),
            null
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("730 days");
        assertThatThrownBy(() -> ReportService.requireWithinFactRowLimit(50_001, "purchase orders"))
            .isInstanceOf(com.enterprise.mm.error.BadRequestException.class)
            .hasMessageContaining("narrow the selected period");
    }

    @Test
    void planFactKeepsCurrenciesInSeparateBucketsWithoutImplicitConversion() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 5, 10, 10, 0);
        PurchaseRequest uzsRequest = request(new BigDecimal("100"), "UZS", createdAt);
        PurchaseRequest usdRequest = request(new BigDecimal("20"), "USD", createdAt);
        PurchaseOrder uzsOrder = order(
            uzsRequest.getId(),
            new BigDecimal("90"),
            "UZS",
            PurchaseOrderStatus.RECEIVED,
            LocalDate.of(2026, 5, 20)
        );
        PurchaseOrder usdOrder = order(
            usdRequest.getId(),
            new BigDecimal("18"),
            "USD",
            PurchaseOrderStatus.SENT,
            LocalDate.of(2026, 5, 20)
        );

        var buckets = ReportService.calculatePlanFactByCurrency(
            List.of(uzsRequest, usdRequest),
            List.of(uzsOrder, usdOrder),
            List.of(receipt(uzsOrder.getId(), "90", LocalDateTime.of(2026, 5, 20, 12, 0))),
            new ReportFilter(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), null)
        );

        assertThat(buckets).extracting("currencyCode").containsExactly("USD", "UZS");
        assertThat(buckets.getFirst().plannedAmount()).isEqualByComparingTo("20");
        assertThat(buckets.getFirst().orderedAmount()).isEqualByComparingTo("18");
        assertThat(buckets.getLast().plannedAmount()).isEqualByComparingTo("100");
        assertThat(buckets.getLast().receivedAmount()).isEqualByComparingTo("90");
    }

    @Test
    void planFactUsesInclusiveSelectedPeriodForRequestsAndOrders() {
        PurchaseRequest before = request(
            new BigDecimal("500"),
            "UZS",
            LocalDateTime.of(2026, 4, 30, 23, 59)
        );
        PurchaseRequest inside = request(
            new BigDecimal("100"),
            "UZS",
            LocalDateTime.of(2026, 5, 1, 0, 0)
        );
        PurchaseOrder insideOrder = order(
            inside.getId(),
            new BigDecimal("90"),
            "UZS",
            PurchaseOrderStatus.RECEIVED,
            LocalDate.of(2026, 5, 31)
        );
        PurchaseOrder afterOrder = order(
            inside.getId(),
            new BigDecimal("80"),
            "UZS",
            PurchaseOrderStatus.RECEIVED,
            LocalDate.of(2026, 6, 1)
        );

        var buckets = ReportService.calculatePlanFactByCurrency(
            List.of(before, inside),
            List.of(insideOrder, afterOrder),
            List.of(
                receipt(insideOrder.getId(), "90", LocalDateTime.of(2026, 5, 31, 23, 59)),
                receipt(afterOrder.getId(), "80", LocalDateTime.of(2026, 6, 1, 0, 0))
            ),
            new ReportFilter(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), "UZS")
        );

        assertThat(buckets).hasSize(1);
        assertThat(buckets.getFirst().plannedAmount()).isEqualByComparingTo("100");
        assertThat(buckets.getFirst().orderedAmount()).isEqualByComparingTo("90");
        assertThat(buckets.getFirst().receivedAmount()).isEqualByComparingTo("90");
    }

    private PurchaseRequest request(BigDecimal total, String currency, LocalDateTime createdAt) {
        PurchaseRequest request = new PurchaseRequest();
        request.setId(UUID.randomUUID());
        request.setTotalAmount(total);
        request.setCurrencyCode(currency);
        request.setCreatedAt(createdAt);
        request.setStatus(PurchaseRequestStatus.APPROVED.name());
        return request;
    }

    private PurchaseOrder order(
        UUID requestId,
        BigDecimal total,
        String currency,
        PurchaseOrderStatus status,
        LocalDate orderDate
    ) {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(UUID.randomUUID());
        order.setPurchaseRequestId(requestId);
        order.setTotalAmount(total);
        order.setCurrencyCode(currency);
        order.setStatus(status.name());
        order.setOrderDate(orderDate);
        return order;
    }

    private StockMovement receipt(UUID orderId, String totalCost, LocalDateTime postedAt) {
        StockMovement movement = new StockMovement();
        movement.setId(UUID.randomUUID());
        movement.setReferenceType("PurchaseOrder");
        movement.setReferenceId(orderId);
        movement.setMovementType(MovementType.RECEIPT);
        movement.setStatus(StockMovementStatus.POSTED.name());
        movement.setPostedAt(postedAt);
        movement.setTotalCost(new BigDecimal(totalCost));
        return movement;
    }
}
