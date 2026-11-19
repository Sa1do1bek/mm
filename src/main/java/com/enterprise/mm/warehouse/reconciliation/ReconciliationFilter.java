package com.enterprise.mm.warehouse.reconciliation;

import java.util.UUID;

public record ReconciliationFilter(
    String accountCode,
    UUID warehouseId,
    UUID materialId,
    ReconciliationStatus status,
    int page,
    int size
) {
    public ReconciliationFilter {
        if (page < 0) page = 0;
        if (size <= 0) size = 50;
    }
}
