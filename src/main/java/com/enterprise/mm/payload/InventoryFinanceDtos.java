package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public final class InventoryFinanceDtos {
    private InventoryFinanceDtos() {
    }

    public record InventoryPeriodClosePrepareRequest(
        LocalDate periodStart,
        LocalDate periodEnd,
        UUID warehouseId,
        String accountingSystem,
        String externalReference,
        BigDecimal accountingValue,
        BigDecimal toleranceAmount,
        Boolean postMatchedReconciliation,
        Boolean closeIfMatched
    ) {
    }

    public record InventoryPeriodClosePrepareResult(
        InventoryPeriodCloseResponse periodClose,
        InventoryAccountingReconciliationResponse accountingReconciliation,
        boolean readyToClose,
        String message
    ) {
    }
}