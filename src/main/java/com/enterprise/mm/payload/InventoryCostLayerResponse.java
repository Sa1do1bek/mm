package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record InventoryCostLayerResponse(
    UUID id,
    UUID materialId,
    UUID warehouseId,
    UUID stockBatchId,
    UUID sourceMovementId,
    String sourceDocumentType,
    UUID sourceDocumentId,
    UUID parentCostLayerId,
    UUID transferTransactionId,
    UUID sourceWarehouseId,
    UUID targetWarehouseId,
    UUID sourceStockMovementId,
    UUID targetStockMovementId,
    String layerType,
    String status,
    BigDecimal receivedQuantity,
    BigDecimal remainingQuantity,
    BigDecimal unitCost,
    BigDecimal totalCost,
    String currencyCode,
    BigDecimal exchangeRate,
    LocalDateTime receivedAt
) {
}
