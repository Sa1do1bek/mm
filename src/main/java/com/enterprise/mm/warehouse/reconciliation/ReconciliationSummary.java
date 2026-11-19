package com.enterprise.mm.warehouse.reconciliation;

import java.math.BigDecimal;

public record ReconciliationSummary(
    long totalPositions,
    long balancedPositions,
    long discrepancyPositions,
    long sourceOnlyPositions,
    long nativeOnlyPositions,
    long unmappedPositions,
    BigDecimal totalSourceQuantity,
    BigDecimal totalSourceAmount,
    BigDecimal totalProvenQuantity,
    BigDecimal totalProvenAmount,
    BigDecimal totalQuantityDifference,
    BigDecimal totalAmountDifference
) {
    public static ReconciliationSummary empty() {
        return new ReconciliationSummary(
            0, 0, 0, 0, 0, 0,
            BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO
        );
    }
}
