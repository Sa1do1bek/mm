package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InventoryOptimizationRecommendationCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
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
