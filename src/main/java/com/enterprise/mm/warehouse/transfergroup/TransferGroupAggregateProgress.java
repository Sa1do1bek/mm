package com.enterprise.mm.warehouse.transfergroup;

import java.math.BigDecimal;

public record TransferGroupAggregateProgress(
    BigDecimal plannedQuantity,
    BigDecimal dispatchedQuantity,
    BigDecimal inTransitQuantity,
    BigDecimal receivedQuantity,
    BigDecimal damagedQuantity,
    BigDecimal missingQuantity,
    BigDecimal cancelledQuantity,
    BigDecimal outstandingQuantity
) {
    public static TransferGroupAggregateProgress zero() {
        return new TransferGroupAggregateProgress(
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO
        );
    }
}
