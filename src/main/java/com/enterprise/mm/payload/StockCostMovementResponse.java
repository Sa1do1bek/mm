package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record StockCostMovementResponse(
    UUID id,
    UUID materialId,
    UUID warehouseId,
    UUID stockMovementId,
    UUID costLayerId,
    String movementType,
    BigDecimal quantity,
    BigDecimal unitCost,
    BigDecimal totalCost,
    UUID costCenterId,
    String sourceDocumentType,
    UUID sourceDocumentId,
    UUID transferTransactionId,
    boolean reversal,
    UUID reversalOfId,
    String reversalReason,
    LocalDateTime reversedAt,
    String reversedBy,
    LocalDateTime createdAt,
    String createdBy
) {
}
