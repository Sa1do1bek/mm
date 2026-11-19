package com.enterprise.mm.payload;

import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.enums.StockValuationRunItemStatus;
import com.enterprise.mm.web.StockValuationErrorMessageSerializer;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record StockValuationRunItemResponse(
    UUID id,
    UUID materialId,
    String materialName,
    UUID warehouseId,
    String warehouseName,
    BigDecimal quantity,
    String unitNameShort,
    ValuationMethod valuationMethod,
    BigDecimal unitCost,
    BigDecimal totalValue,
    BigDecimal carryingValue,
    boolean available,
    @JsonProperty("reason") @JsonSerialize(using = StockValuationErrorMessageSerializer.class) String errorMessage,
    int sequenceNo,
    LocalDateTime calculatedAt
) {
    @JsonProperty("status")
    public String getStatus() {
        return StockValuationRunItemStatus.fromAvailable(available).name();
    }
}
