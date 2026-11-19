package com.enterprise.mm.payload;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BudgetItemResponse extends BaseRecordDto {
    private BigDecimal annualLimit;
    private BigDecimal reservedAmount;
    private BigDecimal committedAmount;
    private BigDecimal usedAmount;
}
