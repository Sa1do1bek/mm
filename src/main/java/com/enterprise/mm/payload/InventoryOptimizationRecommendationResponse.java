package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InventoryOptimizationRecommendationResponse extends BaseRecordDto {
    private UUID materialId;
    private UUID warehouseId;
    private UUID demandForecastId;
    private BigDecimal annualDemand;
    private BigDecimal averageMonthlyDemand;
    private BigDecimal orderCost;
    private BigDecimal holdingCostPerUnit;
    private BigDecimal unitCost;
    private BigDecimal holdingRate;
    private BigDecimal eoqQuantity;
    private BigDecimal orderFrequencyDays;
    private BigDecimal annualOrderingCost;
    private BigDecimal annualHoldingCost;
    private BigDecimal annualTotalCost;
    private Integer leadTimeDays;
    private BigDecimal serviceLevel;
    private BigDecimal recommendedSafetyStock;
    private BigDecimal recommendedReorderPoint;
    private BigDecimal recommendedMinStock;
    private BigDecimal recommendedMaxStock;
    private LocalDateTime generatedAt;
    private LocalDateTime appliedAt;
}