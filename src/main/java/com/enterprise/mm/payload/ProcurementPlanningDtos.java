package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.util.UUID;

public final class ProcurementPlanningDtos {
    private ProcurementPlanningDtos() {
    }

    public record ProcurementPlanningActionRequest(String comment) {
    }

    public record ProcurementPlanConsolidationResult(UUID procurementPlanId, int demandCount, BigDecimal plannedSpendAmount) {
    }
}