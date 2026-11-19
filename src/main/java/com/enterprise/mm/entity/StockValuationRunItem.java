package com.enterprise.mm.entity;

import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.payload.StockValuationCostJobResultResponse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
@Table(
    name = "stock_valuation_run_items",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_stock_valuation_run_item_sequence", columnNames = {"valuation_run_id", "sequence_no"}),
        @UniqueConstraint(name = "uq_stock_valuation_run_item_group", columnNames = {"valuation_run_id", "material_id", "warehouse_id"})
    }
)
public class StockValuationRunItem {
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "valuation_run_id", nullable = false, updatable = false)
    private UUID valuationRunId;

    @Column(name = "sequence_no", nullable = false, updatable = false)
    private int sequenceNo;

    @Column(name = "material_id", nullable = false, updatable = false)
    private UUID materialId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", insertable = false, updatable = false)
    private Material material;

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

    @Column(nullable = false, updatable = false)
    private boolean available;

    @Column(name = "error_message", columnDefinition = "text", updatable = false)
    private String errorMessage;

    @Column(name = "calculated_at", nullable = false, updatable = false)
    private LocalDateTime calculatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (calculatedAt == null) {
            calculatedAt = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
    }

    public static StockValuationRunItem fromJobResult(UUID runId, int sequenceNo, StockValuationCostJobResultResponse result) {
        StockValuationRunItem item = new StockValuationRunItem();
        item.setValuationRunId(runId);
        item.setSequenceNo(sequenceNo);
        item.setMaterialId(result.materialId());
        item.setMaterialName(result.materialName());
        item.setWarehouseId(result.warehouseId());
        item.setWarehouseName(result.warehouseName());
        item.setQuantity(result.quantity());
        item.setValuationMethod(result.valuationMethod());
        item.setTotalValue(result.totalCost());
        item.setUnitCost(unitCost(result));
        item.setCarryingValue(result.totalCost());
        item.setAvailable(result.available());
        item.setErrorMessage(result.errorMessage());
        item.setCalculatedAt(LocalDateTime.now());
        item.setCreatedAt(item.getCalculatedAt());
        return item;
    }

    private static BigDecimal unitCost(StockValuationCostJobResultResponse result) {
        if (result.totalCost() == null || result.quantity() == null || result.quantity().signum() == 0) {
            return null;
        }
        return result.totalCost().divide(result.quantity(), 4, RoundingMode.HALF_UP);
    }
}
