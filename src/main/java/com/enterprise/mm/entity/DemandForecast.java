package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "demand_forecasts")
public class DemandForecast extends BusinessEntity {
    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Column(name = "warehouse_id")
    private UUID warehouseId;

    @Column(name = "forecast_method", nullable = false, length = 40)
    private String forecastMethod;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "history_months", nullable = false)
    private int historyMonths;

    @Column(name = "forecast_months", nullable = false)
    private int forecastMonths;

    @Column(name = "average_monthly_demand", precision = 19, scale = 4)
    private BigDecimal averageMonthlyDemand = BigDecimal.ZERO;

    @Column(name = "forecast_quantity", precision = 22, scale = 7)
    private BigDecimal forecastQuantity = BigDecimal.ZERO;

    @Column(name = "annualized_forecast_quantity", precision = 22, scale = 7)
    private BigDecimal annualizedForecastQuantity = BigDecimal.ZERO;

    @Column(name = "demand_std_dev", precision = 19, scale = 4)
    private BigDecimal demandStdDev = BigDecimal.ZERO;

    @Column(name = "variation_coefficient", precision = 19, scale = 4)
    private BigDecimal variationCoefficient = BigDecimal.ZERO;

    @Column(name = "confidence", precision = 7, scale = 4)
    private BigDecimal confidence = BigDecimal.ZERO;

    @Column(name = "model_quality", length = 30)
    private String modelQuality = "LOW";

    @Column(name = "trend_slope", precision = 19, scale = 4)
    private BigDecimal trendSlope = BigDecimal.ZERO;

    @Column(name = "seasonality_index", precision = 9, scale = 4)
    private BigDecimal seasonalityIndex = BigDecimal.ONE;

    @Column(name = "lead_time_days")
    private Integer leadTimeDays;

    @Column(name = "service_level", precision = 9, scale = 4)
    private BigDecimal serviceLevel = BigDecimal.valueOf(0.95);

    @Column(name = "lead_time_demand", precision = 19, scale = 4)
    private BigDecimal leadTimeDemand = BigDecimal.ZERO;

    @Column(name = "recommended_safety_stock", precision = 19, scale = 4)
    private BigDecimal recommendedSafetyStock = BigDecimal.ZERO;

    @Column(name = "recommended_reorder_point", precision = 19, scale = 4)
    private BigDecimal recommendedReorderPoint = BigDecimal.ZERO;

    @Column(name = "recommended_min_stock", precision = 19, scale = 4)
    private BigDecimal recommendedMinStock = BigDecimal.ZERO;

    @Column(name = "recommended_max_stock", precision = 19, scale = 4)
    private BigDecimal recommendedMaxStock = BigDecimal.ZERO;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    @Column(name = "source", length = 80)
    private String source;
}