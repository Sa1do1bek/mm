package com.enterprise.mm.inventory.allocation;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record StockMovementAllocationDraft(
    String allocationRole,
    UUID balanceId,
    StockMovementStockKeySnapshot sourceStockKey,
    StockMovementStockKeySnapshot targetStockKey,
    UUID uomIdSnapshot,
    String uomCodeSnapshot,
    BigDecimal onHandDelta,
    BigDecimal availableDelta,
    BigDecimal reservedDelta,
    BigDecimal quarantineDelta,
    BigDecimal totalValueDelta,
    BigDecimal batchQuantityDelta,
    BigDecimal unitCostSnapshot,
    String currencyCodeSnapshot,
    BigDecimal exchangeRateSnapshot,
    List<StockMovementCostLineageDraft> costLineages
) {
    public StockMovementAllocationDraft {
        onHandDelta = zeroIfNull(onHandDelta);
        availableDelta = zeroIfNull(availableDelta);
        reservedDelta = zeroIfNull(reservedDelta);
        quarantineDelta = zeroIfNull(quarantineDelta);
        totalValueDelta = zeroIfNull(totalValueDelta);
        batchQuantityDelta = zeroIfNull(batchQuantityDelta);
        costLineages = costLineages == null ? List.of() : List.copyOf(costLineages);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
