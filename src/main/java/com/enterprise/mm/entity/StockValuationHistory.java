package com.enterprise.mm.entity;

import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.payload.StockValuationSelectedStocksResponse.StockCost;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

@Getter
@Setter
@Entity
@Table(name = "stock_valuation_history")
public class StockValuationHistory {
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false, length = 80)
    private String tenantId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "material_id", nullable = false, updatable = false)
    private UUID materialId;

    @Column(name = "material_name", length = 240, updatable = false)
    private String materialName;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @Column(name = "warehouse_name", length = 240, updatable = false)
    private String warehouseName;

    @Column(nullable = false, updatable = false, precision = 22, scale = 7)
    private BigDecimal quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "valuation_method", length = 50, updatable = false)
    private ValuationMethod valuationMethod;

    @Column(name = "unit_cost", precision = 19, scale = 4, updatable = false)
    private BigDecimal unitCost;

    @Column(name = "total_value", precision = 19, scale = 4, updatable = false)
    private BigDecimal totalValue;

    @Column(name = "carrying_value", precision = 19, scale = 4, updatable = false)
    private BigDecimal carryingValue;

    @Column(name = "valuation_basis", length = 80, updatable = false)
    private String valuationBasis;

    @Column(name = "source_type", nullable = false, length = 40, updatable = false)
    private String sourceType;

    @Column(name = "source_id", updatable = false)
    private UUID sourceId;

    @Column(name = "calculated_at", nullable = false, updatable = false)
    private LocalDateTime calculatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (calculatedAt == null) {
            calculatedAt = createdAt;
        }
    }

    public static StockValuationHistory fromJobResult(StockValuationCostJob job, StockCost cost) {
        StockValuationHistory history = new StockValuationHistory();
        history.setTenantId(job.getTenantId());
        history.setCompanyId(job.getCompanyId());
        history.setMaterialId(cost.materialId());
        history.setMaterialName(cost.materialName());
        history.setWarehouseId(cost.warehouseId());
        history.setWarehouseName(cost.warehouseName());
        history.setQuantity(cost.quantity());
        history.setValuationMethod(cost.valuationMethod());
        history.setTotalValue(cost.totalCost());
        history.setUnitCost(unitCost(cost));
        history.setCarryingValue(cost.totalCost());
        history.setValuationBasis("STOCK_COST_JOB");
        history.setSourceType("STOCK_COST_JOB");
        history.setSourceId(job.getId());
        history.setCalculatedAt(LocalDateTime.now());
        history.setCreatedAt(history.getCalculatedAt());
        return history;
    }

    private static BigDecimal unitCost(StockCost cost) {
        if (cost.totalCost() == null || cost.quantity() == null || cost.quantity().signum() == 0) {
            return null;
        }
        return cost.totalCost().divide(cost.quantity(), 4, RoundingMode.HALF_UP);
    }
}
