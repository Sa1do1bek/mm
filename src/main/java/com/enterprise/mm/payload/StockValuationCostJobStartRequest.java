package com.enterprise.mm.payload;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.enterprise.mm.enums.ValuationMethod;
import java.util.UUID;

public record StockValuationCostJobStartRequest(UUID warehouseId, @JsonAlias({"methodType", "method_type", "method", "valuation_method"}) ValuationMethod valuationMethod) {
}
