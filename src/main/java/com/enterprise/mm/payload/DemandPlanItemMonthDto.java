package com.enterprise.mm.payload;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DemandPlanItemMonthDto {
    private Integer month;
    private BigDecimal qty;
    private BigDecimal approvedQty;
    private BigDecimal reservedQty;
}
