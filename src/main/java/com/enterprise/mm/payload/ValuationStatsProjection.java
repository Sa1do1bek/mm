package com.enterprise.mm.payload;

import java.math.BigDecimal;

public interface ValuationStatsProjection {
    BigDecimal getTotalValue();
    Long getMaterialsCount();
}
