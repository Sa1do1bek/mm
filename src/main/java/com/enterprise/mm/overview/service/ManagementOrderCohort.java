package com.enterprise.mm.overview.service;

import com.enterprise.mm.entity.PurchaseOrder;
import java.time.LocalDate;
import java.util.List;

public final class ManagementOrderCohort {
    private static final List<String> OPEN_STATUSES = List.of(
        "DRAFT", "APPROVAL_PENDING", "APPROVED", "SENT", "PARTIALLY_RECEIVED"
    );

    private ManagementOrderCohort() {
    }

    public static List<String> openStatuses() {
        return OPEN_STATUSES;
    }

    public static boolean isOpen(PurchaseOrder order) {
        return order != null && OPEN_STATUSES.contains(order.getStatus());
    }

    public static boolean isDatedOpen(PurchaseOrder order) {
        return isOpen(order) && order.getExpectedDeliveryDate() != null;
    }

    public static boolean isOverdueOpen(PurchaseOrder order, LocalDate today) {
        return isDatedOpen(order) && order.getExpectedDeliveryDate().isBefore(today);
    }
}
