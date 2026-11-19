package com.enterprise.mm.inventory.allocation;

import java.math.BigDecimal;
import java.util.UUID;

public record StockMovementCostLineageDraft(
    String lineageRole,
    UUID costLayerId,
    UUID parentCostLayerIdSnapshot,
    UUID sourceCostMovementId,
    BigDecimal quantityDelta,
    BigDecimal valueDelta,
    BigDecimal unitCostSnapshot,
    String currencyCodeSnapshot,
    BigDecimal exchangeRateSnapshot
) {
    public StockMovementCostLineageDraft {
        quantityDelta = quantityDelta == null ? BigDecimal.ZERO : quantityDelta;
        valueDelta = valueDelta == null ? BigDecimal.ZERO : valueDelta;
    }
}
