package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.util.UUID;

public record MaterialAdviceDto(
    UUID warehouseId,
    BigDecimal availableQuantity,
    BigDecimal minStock,
    Integer replenishmentLeadTimeDays,
    Integer periodDays,
    BigDecimal consumptionQuantity,
    BigDecimal averageDailyUsage,
    Integer daysUntilMinStock,
    Integer leadTimeGapDays,
    String status
) {
}
