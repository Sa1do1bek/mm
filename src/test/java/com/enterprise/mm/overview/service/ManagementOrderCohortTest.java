package com.enterprise.mm.overview.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.entity.PurchaseOrder;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ManagementOrderCohortTest {

    @Test
    void slaDenominatorAndOverdueNumeratorUseTheSameDatedOpenOrderCohort() {
        LocalDate today = LocalDate.of(2026, 7, 15);

        assertThat(ManagementOrderCohort.isDatedOpen(order("SENT", today.minusDays(1)))).isTrue();
        assertThat(ManagementOrderCohort.isOverdueOpen(order("SENT", today.minusDays(1)), today)).isTrue();
        assertThat(ManagementOrderCohort.isDatedOpen(order("PARTIALLY_RECEIVED", today.plusDays(1)))).isTrue();
        assertThat(ManagementOrderCohort.isOverdueOpen(
            order("PARTIALLY_RECEIVED", today.plusDays(1)), today)).isFalse();

        for (String excluded : new String[] {"REJECTED", "RETURNED", "RECEIVED", "CLOSED", "CANCELLED"}) {
            PurchaseOrder order = order(excluded, today.minusDays(10));
            assertThat(ManagementOrderCohort.isDatedOpen(order)).as(excluded).isFalse();
            assertThat(ManagementOrderCohort.isOverdueOpen(order, today)).as(excluded).isFalse();
        }
        assertThat(ManagementOrderCohort.isDatedOpen(order("APPROVED", null))).isFalse();
    }

    private PurchaseOrder order(String status, LocalDate expectedDeliveryDate) {
        PurchaseOrder order = new PurchaseOrder();
        order.setStatus(status);
        order.setExpectedDeliveryDate(expectedDeliveryDate);
        return order;
    }
}
