package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportReceiptFactTest {

    @Test
    void receivedAmountUsesUniquePostedPartialReceiptFactsInsteadOfFinalOrderStatus() {
        ReportFilter filter = new ReportFilter(
            LocalDate.of(2026, 5, 1),
            LocalDate.of(2026, 5, 31),
            "UZS"
        );
        PurchaseRequest request = request("100", "UZS");
        PurchaseOrder order = order(
            request.getId(),
            "100",
            "UZS",
            BigDecimal.ONE,
            PurchaseOrderStatus.PARTIALLY_RECEIVED
        );
        StockMovement partialReceipt = receipt(order.getId(), "25", LocalDateTime.of(2026, 5, 20, 10, 0));
        StockMovement draftReceipt = receipt(order.getId(), "30", LocalDateTime.of(2026, 5, 21, 10, 0));
        draftReceipt.setStatus(StockMovementStatus.DRAFT.name());
        StockMovement outsidePeriod = receipt(order.getId(), "40", LocalDateTime.of(2026, 6, 1, 0, 0));

        var buckets = ReportService.calculatePlanFactByCurrency(
            List.of(request),
            List.of(order),
            List.of(partialReceipt, partialReceipt, draftReceipt, outsidePeriod),
            filter
        );

        assertThat(buckets).hasSize(1);
        assertThat(buckets.getFirst().orderedAmount()).isEqualByComparingTo("100");
        assertThat(buckets.getFirst().receivedAmount()).isEqualByComparingTo("25");
    }

    @Test
    void receivedAmountConvertsPostedBaseCostBackToPurchaseOrderCurrencyUsingRecordedRate() {
        ReportFilter filter = new ReportFilter(
            LocalDate.of(2026, 5, 1),
            LocalDate.of(2026, 5, 31),
            "USD"
        );
        PurchaseRequest request = request("100", "USD");
        PurchaseOrder order = order(
            request.getId(),
            "100",
            "USD",
            new BigDecimal("12500"),
            PurchaseOrderStatus.PARTIALLY_RECEIVED
        );
        StockMovement partialReceipt = receipt(
            order.getId(),
            "250000",
            LocalDateTime.of(2026, 5, 20, 10, 0)
        );

        var buckets = ReportService.calculatePlanFactByCurrency(
            List.of(request),
            List.of(order),
            List.of(partialReceipt),
            filter
        );

        assertThat(buckets).hasSize(1);
        assertThat(buckets.getFirst().receivedAmount()).isEqualByComparingTo("20.0000");
    }

    private PurchaseRequest request(String amount, String currency) {
        PurchaseRequest request = new PurchaseRequest();
        request.setId(UUID.randomUUID());
        request.setStatus(PurchaseRequestStatus.APPROVED.name());
        request.setTotalAmount(new BigDecimal(amount));
        request.setCurrencyCode(currency);
        request.setCreatedAt(LocalDateTime.of(2026, 5, 2, 9, 0));
        return request;
    }

    private PurchaseOrder order(
        UUID requestId,
        String amount,
        String currency,
        BigDecimal exchangeRate,
        PurchaseOrderStatus status
    ) {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(UUID.randomUUID());
        order.setPurchaseRequestId(requestId);
        order.setStatus(status.name());
        order.setTotalAmount(new BigDecimal(amount));
        order.setCurrencyCode(currency);
        order.setExchangeRate(exchangeRate);
        order.setOrderDate(LocalDate.of(2026, 5, 5));
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
