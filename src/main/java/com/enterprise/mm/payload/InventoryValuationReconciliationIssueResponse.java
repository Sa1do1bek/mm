package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.util.UUID;

public record InventoryValuationReconciliationIssueResponse(
    UUID id,
    String issueType,
    String severity,
    UUID companyId,
    UUID materialId,
    UUID warehouseId,
    UUID stockBatchId,
    UUID stockMovementId,
    UUID costLayerId,
    UUID costMovementId,
    BigDecimal balanceQuantity,
    BigDecimal layerQuantity,
    BigDecimal balanceValue,
    BigDecimal layerValue,
    String message
) {
}
