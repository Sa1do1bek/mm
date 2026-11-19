package com.enterprise.mm.enums;

import java.util.Set;

public enum InventoryStatus {
    DRAFT,
    PLANNED,
    IN_PROGRESS,
    COUNTING_COMPLETED,
    DISCREPANCY_REVIEW,
    WAITING_APPROVAL,
    APPROVED,
    REJECTED,
    ADJUSTMENT_POSTED,
    CLOSED,
    CANCELLED;

    private static final Set<InventoryStatus> EDITABLE = Set.of(DRAFT, PLANNED);
    private static final Set<InventoryStatus> COUNTABLE = Set.of(IN_PROGRESS, COUNTING_COMPLETED, DISCREPANCY_REVIEW);
    private static final Set<InventoryStatus> DELETABLE = Set.of(DRAFT, PLANNED, CANCELLED);

    public static InventoryStatus from(String value) {
        if (value == null || value.isBlank()) {
            return DRAFT;
        }
        return InventoryStatus.valueOf(value.trim().toUpperCase());
    }

    public boolean editable() {
        return EDITABLE.contains(this);
    }

    public boolean countable() {
        return COUNTABLE.contains(this);
    }

    public boolean deletable() {
        return DELETABLE.contains(this);
    }
}
