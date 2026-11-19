package com.enterprise.mm.warehouse.reconciliation;

import java.util.List;

public record WarehouseReconciliationResult(
    ReconciliationSummary summary,
    List<ReconciliationPosition> items,
    int page,
    int size,
    long totalCount
) {}
