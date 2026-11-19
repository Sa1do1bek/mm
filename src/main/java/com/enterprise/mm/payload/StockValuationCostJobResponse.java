package com.enterprise.mm.payload;

import com.enterprise.mm.enums.ValuationMethod;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record StockValuationCostJobResponse(
    UUID id,
    UUID warehouseId,
    ValuationMethod valuationMethod,
    String requestedBy,
    String status,
    String phase,
    BigDecimal progressPercent,
    int totalItems,
    int processedItems,
    int successfulItems,
    int failedItems,
    String errorMessage,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
