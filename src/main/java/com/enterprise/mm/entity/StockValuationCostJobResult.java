package com.enterprise.mm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.payload.StockValuationSelectedStocksResponse.StockCost;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

@Getter
@Setter
@Entity
@Table(
    name = "stock_valuation_cost_job_results",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_stock_valuation_job_result_sequence", columnNames = {"job_id", "sequence_no"}),
        @UniqueConstraint(name = "uq_stock_valuation_job_result_group", columnNames = {"job_id", "material_id", "warehouse_id"})
    }
)
public class StockValuationCostJobResult {
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @Column(name = "sequence_no", nullable = false, updatable = false)
    private int sequenceNo;

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

    @Column(name = "total_cost", precision = 19, scale = 4, updatable = false)
    private BigDecimal totalCost;

    @Column(nullable = false, updatable = false)
    private boolean available;

    @Column(name = "error_message", columnDefinition = "text", updatable = false)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public static StockValuationCostJobResult from(UUID jobId, int sequenceNo, StockCost cost) {
        StockValuationCostJobResult result = new StockValuationCostJobResult();
        result.setJobId(jobId);
        result.setSequenceNo(sequenceNo);
        result.setMaterialId(cost.materialId());
        result.setMaterialName(cost.materialName());
        result.setWarehouseId(cost.warehouseId());
        result.setWarehouseName(cost.warehouseName());
        result.setQuantity(cost.quantity());
        result.setValuationMethod(cost.valuationMethod());
        result.setTotalCost(cost.totalCost());
        result.setAvailable(cost.available());
        result.setErrorMessage(cost.errorMessage());
        result.setCreatedAt(LocalDateTime.now());
        return result;
    }
}
