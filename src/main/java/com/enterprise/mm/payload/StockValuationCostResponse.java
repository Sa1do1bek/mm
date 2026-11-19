package com.enterprise.mm.payload;

import java.math.BigDecimal;
import com.enterprise.mm.enums.ValuationMethod;

public record StockValuationCostResponse(
    ValuationMethod valuationMethod,
    BigDecimal totalCost
) {
}
