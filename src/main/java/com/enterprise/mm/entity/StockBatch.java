package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "stock_batches")
public class StockBatch extends BusinessEntity {
    @Column(name = "material_id", nullable = false)
    private UUID materialId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", insertable = false, updatable = false)
    private Material material;
    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", insertable = false, updatable = false)
    private Warehouse warehouse;
    @Column(length = 120)
    private String batchNumber;
    @Column(length = 120)
    private String serialNumber;
    @Column(length = 160)
    private String barcode;
    @Column(length = 160)
    private String qrCode;
    @Column(length = 160)
    private String rfidTag;
    @Column(precision = 22, scale = 7)
    private BigDecimal quantity = BigDecimal.ZERO;
    private LocalDate receivedDate;
    private LocalDate expirationDate;
    @Column(name = "manufacturer_batch_number", length = 160)
    private String manufacturerBatchNumber;
    @Column(name = "manufactured_on")
    private LocalDate manufacturedOn;
    @Column(name = "expires_on")
    private LocalDate expiresOn;
    @Column(precision = 19, scale = 4)
    private BigDecimal unitCost = BigDecimal.ZERO;
    @Column(name = "purchase_unit_cost", nullable = false, precision = 19, scale = 6)
    private BigDecimal purchaseUnitCost = BigDecimal.ZERO;
    @Column(name = "additional_cost_per_unit", nullable = false, precision = 19, scale = 6)
    private BigDecimal additionalCostPerUnit = BigDecimal.ZERO;
    @Column(name = "final_unit_cost", nullable = false, precision = 19, scale = 6)
    private BigDecimal finalUnitCost = BigDecimal.ZERO;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        if (material != null) {
            materialId = material.getId();
        }
        if (warehouse != null) {
            warehouseId = warehouse.getId();
        }
    }
}
