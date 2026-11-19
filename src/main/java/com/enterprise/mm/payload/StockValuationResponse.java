package com.enterprise.mm.payload;

import java.math.BigDecimal;
import com.enterprise.mm.enums.ValuationMethod;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StockValuationResponse extends BaseRecordDto {
    private UUID materialId;
    private UUID warehouseId;
    private ValuationMethod valuationMethod;
    private BigDecimal quantity;
    private BigDecimal unitCost;
    private BigDecimal totalValue;
    private BigDecimal impairmentReserve;
    private BigDecimal revaluationAmount;
    private BigDecimal carryingValue;
    private String valuationBasis;
    private LocalDateTime calculatedAt;
}