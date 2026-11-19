package com.enterprise.mm.enums;

import java.util.List;

public enum StockMovementStatus {
    DRAFT,
    PENDING,
    APPROVED,
    RETURNED,
    POSTED,
    CANCELLED;

    public static List<String> effectiveStatusNames() {
        return List.of(POSTED.name());
    }

    public static boolean isEffective(String status) {
        return POSTED.name().equals(status);
    }
}
