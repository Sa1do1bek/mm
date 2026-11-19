package com.enterprise.mm.payload;

import java.math.BigDecimal;

public record ServiceRemainingContractResponse(
    BigDecimal orderedAmount,
    BigDecimal acceptedAmount,
    BigDecimal remainingAmount,
    String currency
) {
}
