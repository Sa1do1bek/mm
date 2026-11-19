package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.util.UUID;

public record ContractLimitResult(
    UUID contractId,
    boolean approved,
    BigDecimal requestedAmount,
    BigDecimal remainingAmount,
    BigDecimal usedAmount,
    BigDecimal serviceUsedAmount,
    String message
) {
    public boolean allowed() {
        return approved;
    }

    public BigDecimal limitAmount() {
        return remainingAmount == null || usedAmount == null ? remainingAmount : remainingAmount.add(usedAmount);
    }
}
