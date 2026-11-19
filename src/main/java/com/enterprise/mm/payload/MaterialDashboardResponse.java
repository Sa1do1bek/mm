package com.enterprise.mm.payload;

import java.math.BigDecimal;

public record MaterialDashboardResponse(
        long totalMaterials,
        long lowStock,
        long outOfStock,
        BigDecimal value
) {
}
