package com.enterprise.mm.payload;

import java.math.BigDecimal;
import com.enterprise.mm.enums.ValuationMethod;
import java.time.LocalDateTime;
import java.util.UUID;

public record StockValuationHistoryResponse(
    UUID id,
    UUID materialId,
    String materialName,
    UUID warehouseId,
    String warehouseName,
    BigDecimal quantity,
    ValuationMethod valuationMethod,
    BigDecimal unitCost,
    BigDecimal totalValue,
    BigDecimal carryingValue,
    String valuationBasis,
    String sourceType,
    UUID sourceId,
    LocalDateTime calculatedAt,
    LocalDateTime createdAt
) {
}
