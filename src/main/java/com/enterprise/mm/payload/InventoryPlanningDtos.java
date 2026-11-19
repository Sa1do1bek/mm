package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class InventoryPlanningDtos {
    private InventoryPlanningDtos() {
    }

    public record DemandForecastRequest(
        UUID materialId,
        UUID warehouseId,
        String forecastMethod,
        Integer historyMonths,
        Integer forecastMonths,
        Integer leadTimeDays,
        BigDecimal serviceLevel
    ) {
    }

    public record EoqCalculationRequest(
        UUID materialId,
        UUID warehouseId,
        UUID demandForecastId,
        BigDecimal annualDemand,
        BigDecimal orderCost,
        BigDecimal holdingCostPerUnit,
        BigDecimal unitCost,
        BigDecimal holdingRate,
        Integer leadTimeDays,
        BigDecimal serviceLevel
    ) {
    }

    public record ForecastBatchRequest(
        UUID warehouseId,
        String forecastMethod,
        Integer historyMonths,
        Integer forecastMonths,
        Integer leadTimeDays,
        BigDecimal serviceLevel,
        BigDecimal orderCost,
        BigDecimal holdingRate,
        boolean calculateEoq,
        boolean applyToMaterial
    ) {
    }

    public record ForecastBatchResult(
        int materialCount,
        int forecastCount,
        int eoqRecommendationCount,
        int appliedRecommendationCount,
        List<UUID> forecastIds,
        List<UUID> recommendationIds
    ) {
    }

    public record VmiReviewResult(
        UUID agreementId,
        UUID supplierId,
        UUID materialId,
        UUID warehouseId,
        BigDecimal availableQuantity,
        BigDecimal reorderPoint,
        BigDecimal targetStock,
        BigDecimal recommendedReplenishmentQuantity,
        boolean replenishmentRequired,
        UUID integrationEventId
    ) {
    }

    public record ConsignmentConsumptionRequest(
        UUID stockMovementId,
        BigDecimal quantity,
        BigDecimal unitCost,
        String settlementReference,
        String comment
    ) {
    }
}