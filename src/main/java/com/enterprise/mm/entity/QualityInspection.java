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
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "quality_inspections")
public class QualityInspection extends BusinessEntity {
    @Column(name = "inspection_number", length = 120)
    private String inspectionNumber;

    @Column(name = "inspection_type", nullable = false, length = 40)
    private String inspectionType = "INCOMING";

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

    @Column(name = "received_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal receivedQuantity = BigDecimal.ZERO;

    @Column(name = "sample_quantity", precision = 22, scale = 7)
    private BigDecimal sampleQuantity = BigDecimal.ZERO;

    @Column(name = "accepted_quantity", precision = 22, scale = 7)
    private BigDecimal acceptedQuantity = BigDecimal.ZERO;

    @Column(name = "rejected_quantity", precision = 22, scale = 7)
    private BigDecimal rejectedQuantity = BigDecimal.ZERO;

    @Column(name = "quarantined_quantity", precision = 22, scale = 7)
    private BigDecimal quarantinedQuantity = BigDecimal.ZERO;

    @Column(name = "inspection_date")
    private LocalDate inspectionDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "inspector_employee_id")
    private UUID inspectorEmployeeId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inspector_employee_id", insertable = false, updatable = false)
    private Employee inspectorEmployee;

    @Column(name = "quality_passport_attachment_id")
    private UUID qualityPassportAttachmentId;

    @Column(name = "quarantine_started_at")
    private LocalDateTime quarantineStartedAt;

    @Column(name = "quarantine_released_at")
    private LocalDateTime quarantineReleasedAt;

    @Column(name = "inspection_started_at")
    private LocalDateTime inspectionStartedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "conclusion", columnDefinition = "text")
    private String conclusion;

    @PrePersist
    @PreUpdate
    void syncRelations() {
        supplierId = supplier == null ? supplierId : supplier.getId();
        purchaseOrderId = purchaseOrder == null ? purchaseOrderId : purchaseOrder.getId();
        purchaseOrderItemId = purchaseOrderItem == null ? purchaseOrderItemId : purchaseOrderItem.getId();
        stockMovementId = stockMovement == null ? stockMovementId : stockMovement.getId();
        materialId = material == null ? materialId : material.getId();
        warehouseId = warehouse == null ? warehouseId : warehouse.getId();
        storageLocationId = storageLocation == null ? storageLocationId : storageLocation.getId();
        stockBatchId = stockBatch == null ? stockBatchId : stockBatch.getId();
        inspectorEmployeeId = inspectorEmployee == null ? inspectorEmployeeId : inspectorEmployee.getId();
    }
}
