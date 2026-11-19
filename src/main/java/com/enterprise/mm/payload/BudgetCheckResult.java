package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.util.UUID;

public record BudgetCheckResult(
    UUID budgetItemId,
    boolean approved,
    boolean requiresEscalation,
    BigDecimal requestedAmount,
    BigDecimal availableAmount,
    BigDecimal reservedAmount,
    BigDecimal consumedAmount,
    String message
) {
    public boolean sufficient() {
        return approved;
    }
}
