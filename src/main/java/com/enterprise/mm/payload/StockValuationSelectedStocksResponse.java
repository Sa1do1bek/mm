package com.enterprise.mm.payload;

import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.web.StockValuationErrorMessageSerializer;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record StockValuationSelectedStocksResponse(
    List<StockCost> stocks
) {
    public record StockCost(
        UUID materialId,
        String materialName,
        UUID warehouseId,
        String warehouseName,
        BigDecimal quantity,
        ValuationMethod valuationMethod,
        BigDecimal totalCost,
        boolean available,
        @JsonProperty("reason") @JsonSerialize(using = StockValuationErrorMessageSerializer.class) String errorMessage
    ) {
        @JsonProperty("status")
        public String getStatus() {
            return available ? "COMPLETED" : "FAILED";
        }
    }
}
