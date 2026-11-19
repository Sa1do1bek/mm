package com.enterprise.mm.payload;

import java.math.BigDecimal;

public record ValuationStatsDto(
    BigDecimal totalValue,
    Long materialsCount,
    BigDecimal totalImpairment
) {}
