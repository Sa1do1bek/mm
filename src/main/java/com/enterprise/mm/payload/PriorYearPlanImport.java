package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PriorYearPlanImport(Integer year, List<Line> lines) {
    public record Line(String planNumber, String name, UUID materialGroupId, BigDecimal plannedSpendAmount, String currencyCode) {
    }
}
