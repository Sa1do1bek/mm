package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.util.UUID;

public record BudgetLedgerReconciliationResult(
    UUID budgetItemId,
    boolean matched,
    BigDecimal storedReservedAmount,
    BigDecimal ledgerReservedAmount,
    BigDecimal reservedDifference,
    BigDecimal storedCommittedAmount,
    BigDecimal ledgerCommittedAmount,
    BigDecimal committedDifference,
    BigDecimal storedUsedAmount,
    BigDecimal ledgerUsedAmount,
    BigDecimal usedDifference
) {
}
