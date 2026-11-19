package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "inventory_optimization_recommendations")
public class InventoryOptimizationRecommendation extends BusinessEntity {
    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Column(name = "warehouse_id")
    private UUID warehouseId;

    @Column(name = "demand_forecast_id")
    private UUID demandForecastId;

    @Column(name = "annual_demand", nullable = false, precision = 19, scale = 4)
    private BigDecimal annualDemand = BigDecimal.ZERO;

    @Column(name = "average_monthly_demand", precision = 19, scale = 4)
    private BigDecimal averageMonthlyDemand = BigDecimal.ZERO;

    @Column(name = "order_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal orderCost = BigDecimal.ZERO;

    @Column(name = "holding_cost_per_unit", nullable = false, precision = 19, scale = 4)
    private BigDecimal holdingCostPerUnit = BigDecimal.ZERO;

    @Column(name = "unit_cost", precision = 19, scale = 4)
    private BigDecimal unitCost = BigDecimal.ZERO;

    @Column(name = "holding_rate", precision = 9, scale = 4)
    private BigDecimal holdingRate = BigDecimal.ZERO;

    @Column(name = "eoq_quantity", precision = 22, scale = 7)
    private BigDecimal eoqQuantity = BigDecimal.ZERO;

    @Column(name = "order_frequency_days", precision = 19, scale = 4)
    private BigDecimal orderFrequencyDays = BigDecimal.ZERO;

    @Column(name = "annual_ordering_cost", precision = 19, scale = 4)
    private BigDecimal annualOrderingCost = BigDecimal.ZERO;

    @Column(name = "annual_holding_cost", precision = 19, scale = 4)
    private BigDecimal annualHoldingCost = BigDecimal.ZERO;

    @Column(name = "annual_total_cost", precision = 19, scale = 4)
    private BigDecimal annualTotalCost = BigDecimal.ZERO;

    @Column(name = "lead_time_days")
    private Integer leadTimeDays;

    @Column(name = "service_level", precision = 9, scale = 4)
    private BigDecimal serviceLevel = BigDecimal.ZERO;

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

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;
}