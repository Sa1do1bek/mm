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
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "quality_quarantine_lots")
public class QualityQuarantineLot extends BusinessEntity {
    @Column(name="transfer_allocation_id") private UUID transferAllocationId;
    @Column(name="transfer_clearance_id") private UUID transferClearanceId;
    @Column(name = "quarantine_number", length = 120)
    private String quarantineNumber;

    @Column(name = "quality_inspection_id")
    private UUID qualityInspectionId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quality_inspection_id", insertable = false, updatable = false)
    private QualityInspection qualityInspection;

    @Column(name = "quality_nonconformance_id")
    private UUID qualityNonconformanceId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quality_nonconformance_id", insertable = false, updatable = false)
    private QualityNonconformance qualityNonconformance;

    @Column(name = "supplier_id")
    private UUID supplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "purchase_order_id")
    private UUID purchaseOrderId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", insertable = false, updatable = false)
    private PurchaseOrder purchaseOrder;

    @Column(name = "purchase_order_item_id")
    private UUID purchaseOrderItemId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_item_id", insertable = false, updatable = false)
    private PurchaseOrderItem purchaseOrderItem;

    @Column(name = "stock_movement_id")
    private UUID stockMovementId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_movement_id", insertable = false, updatable = false)
    private StockMovement stockMovement;

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

    @Column(name = "storage_location_id")
    private UUID storageLocationId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "storage_location_id", insertable = false, updatable = false)
    private StorageLocation storageLocation;

    @Column(name = "stock_batch_id")
    private UUID stockBatchId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_batch_id", insertable = false, updatable = false)
    private StockBatch stockBatch;

    @Column(name = "quarantine_reason", columnDefinition = "text")
    private String quarantineReason;

    @Column(name = "quarantined_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal quarantinedQuantity = BigDecimal.ZERO;

    @Column(name = "released_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal releasedQuantity = BigDecimal.ZERO;

    @Column(name = "rejected_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal rejectedQuantity = BigDecimal.ZERO;

    @Column(name = "remaining_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal remainingQuantity = BigDecimal.ZERO;

    @Column(name = "disposition", length = 80)
    private String disposition = "QUARANTINE";

    @Column(name = "responsible_employee_id")
    private UUID responsibleEmployeeId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "responsible_employee_id", insertable = false, updatable = false)
    private Employee responsibleEmployee;

    @Column(name = "quarantine_started_at")
    private LocalDateTime quarantineStartedAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @PrePersist
    @PreUpdate
    void syncRelations() {
        qualityInspectionId = qualityInspection == null ? qualityInspectionId : qualityInspection.getId();
        qualityNonconformanceId = qualityNonconformance == null ? qualityNonconformanceId : qualityNonconformance.getId();
        supplierId = supplier == null ? supplierId : supplier.getId();
        purchaseOrderId = purchaseOrder == null ? purchaseOrderId : purchaseOrder.getId();
        purchaseOrderItemId = purchaseOrderItem == null ? purchaseOrderItemId : purchaseOrderItem.getId();
        stockMovementId = stockMovement == null ? stockMovementId : stockMovement.getId();
        materialId = material == null ? materialId : material.getId();
        warehouseId = warehouse == null ? warehouseId : warehouse.getId();
        storageLocationId = storageLocation == null ? storageLocationId : storageLocation.getId();
        stockBatchId = stockBatch == null ? stockBatchId : stockBatch.getId();
        responsibleEmployeeId = responsibleEmployee == null ? responsibleEmployeeId : responsibleEmployee.getId();
    }
}
