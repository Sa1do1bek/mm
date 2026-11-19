package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DemandForecastCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID materialId;
    private UUID warehouseId;
    private String forecastMethod;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private int historyMonths;
    private int forecastMonths;
    private BigDecimal averageMonthlyDemand;
    private BigDecimal forecastQuantity;
    private BigDecimal annualizedForecastQuantity;
    private BigDecimal demandStdDev;
    private BigDecimal variationCoefficient;
    private BigDecimal confidence;
    private String modelQuality;
    private BigDecimal trendSlope;
    private BigDecimal seasonalityIndex;
    private Integer leadTimeDays;
    private BigDecimal serviceLevel;
    private BigDecimal leadTimeDemand;
    private BigDecimal recommendedSafetyStock;
    private BigDecimal recommendedReorderPoint;
    private BigDecimal recommendedMinStock;
    private BigDecimal recommendedMaxStock;
    private LocalDateTime generatedAt;
    private String source;
}
