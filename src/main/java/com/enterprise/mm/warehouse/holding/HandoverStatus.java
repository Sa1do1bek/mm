package com.enterprise.mm.warehouse.holding;

public enum HandoverStatus {
    PENDING,
    ACKNOWLEDGED,
    PARTIALLY_ACKNOWLEDGED,
    DISCREPANCY_REVIEW,
    REJECTED,
    CANCELLED
}
