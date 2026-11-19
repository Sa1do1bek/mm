package com.enterprise.mm.payload;

import com.enterprise.mm.enums.StockRiskSeverity;
import java.math.BigDecimal;
import java.util.UUID;

public record StockRiskAlertResponse(
    UUID materialId,
    String materialCode,
    String materialName,
    UUID unitOfMeasureId,
    String unitOfMeasureCode,
    String unitOfMeasureShortName,
    String unitOfMeasureLongName,
    UUID companyId,
    UUID warehouseId,
    BigDecimal availableQuantity,
    BigDecimal minStock,
    BigDecimal safetyStock,
    BigDecimal reorderPoint,
    int periodDays,
    BigDecimal consumptionQuantity,
    BigDecimal averageDailyUsage,
    Integer daysUntilMinStock,
    Integer daysUntilOutOfStock,
    StockRiskSeverity severity,
    boolean risk,
    String message
) {
}
