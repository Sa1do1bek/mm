package com.enterprise.mm.overview.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.Supplier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SupplierExposureCalculatorTest {
    private static final UUID SUPPLIER_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");

    @Test
    void usesRemainingLineValueAndScheduleHealthFromDatedOpenOrdersOnly() {
        PurchaseOrder sentOverdue = order("SENT", LocalDate.of(2026, 7, 10), "900");
        PurchaseOrder partialUndated = order("PARTIALLY_RECEIVED", null, "700");
        PurchaseOrder completed = order("CLOSED", LocalDate.of(2026, 7, 1), "5000");
        Supplier supplier = new Supplier();
        supplier.setId(SUPPLIER_ID);
        supplier.setCode("SUP-1");
        supplier.setName("Supplier One");
        supplier.setRating(new BigDecimal("84"));

        var result = new SupplierExposureCalculator().calculate(
            List.of(sentOverdue, partialUndated, completed),
            Map.of(SUPPLIER_ID, supplier),
            Map.of(sentOverdue.getId(), new BigDecimal("250"), partialUndated.getId(), new BigDecimal("300")),
            "UZS",
            LocalDate.of(2026, 7, 15)
        );

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().openOutstandingValue()).isEqualByComparingTo("550");
        assertThat(result.getFirst().openOrders()).isEqualTo(2);
        assertThat(result.getFirst().datedOpenOrders()).isEqualTo(1);
        assertThat(result.getFirst().openScheduleHealthPercent()).isEqualByComparingTo("0.00");
    }

    @Test
    void omitsSuppliersWithoutOpenOrdersAndLeavesUndatedScheduleHealthUnavailable() {
        PurchaseOrder undated = order("APPROVED", null, "1000");

        var result = new SupplierExposureCalculator().calculate(
            List.of(undated), Map.of(), Map.of(undated.getId(), new BigDecimal("400")), "UZS",
            LocalDate.of(2026, 7, 15));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().openScheduleHealthPercent()).isNull();
        assertThat(result.getFirst().datedOpenOrders()).isZero();
    }

    private PurchaseOrder order(String status, LocalDate expectedDeliveryDate, String totalAmount) {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(UUID.randomUUID());
        order.setSupplierId(SUPPLIER_ID);
        order.setStatus(status);
        order.setExpectedDeliveryDate(expectedDeliveryDate);
        order.setTotalAmount(new BigDecimal(totalAmount));
        return order;
    }
}
