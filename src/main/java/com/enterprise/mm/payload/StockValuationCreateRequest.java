package com.enterprise.mm.payload;

import com.enterprise.mm.common.LenientLocalDateTimeDeserializer;
import com.enterprise.mm.enums.ValuationMethod;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StockValuationCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
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
    @JsonDeserialize(using = LenientLocalDateTimeDeserializer.class)
    private LocalDateTime calculatedAt;
}
