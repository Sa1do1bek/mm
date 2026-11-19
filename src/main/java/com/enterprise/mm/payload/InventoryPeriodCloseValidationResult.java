package com.enterprise.mm.payload;

import java.util.List;
import java.util.UUID;

public record InventoryPeriodCloseValidationResult(
    UUID periodCloseId,
    String status,
    long blockerCount,
    long warningCount,
    List<InventoryValuationReconciliationIssueResponse> issues
) {
}
