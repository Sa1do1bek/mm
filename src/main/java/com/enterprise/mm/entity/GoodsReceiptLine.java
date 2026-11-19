package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Immutable line snapshot preserving the exact receipt outcome and stock lineage. */
@Getter
@Setter
@Entity
@Table(name = "goods_receipt_lines")
public class GoodsReceiptLine extends BusinessEntity {
    @Column(name = "goods_receipt_id", nullable = false, updatable = false)
    private UUID goodsReceiptId;
    @Column(name = "purchase_order_id", nullable = false, updatable = false)
    private UUID purchaseOrderId;
    @Column(name = "purchase_order_item_id", nullable = false, updatable = false)
    private UUID purchaseOrderItemId;
    @Column(name = "supplier_id", nullable = false, updatable = false)
    private UUID supplierId;
    @Column(name = "material_id", nullable = false, updatable = false)
    private UUID materialId;
    @Column(name = "material_group_id", updatable = false)
    private UUID materialGroupId;
    @Column(name = "uom_code", length = 80, updatable = false)
    private String uomCode;
    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;
    @Column(name = "storage_location_id", updatable = false)
    private UUID storageLocationId;
    @Column(name = "storage_bin_id", updatable = false)
    private UUID storageBinId;
    @Column(name = "batch_number", length = 120, updatable = false)
    private String batchNumber;
    @Column(name = "open_quantity_before", precision = 22, scale = 7, nullable = false, updatable = false)
    private BigDecimal openQuantityBefore = BigDecimal.ZERO;
    @Column(name = "physical_received_quantity", precision = 22, scale = 7, nullable = false, updatable = false)
    private BigDecimal physicalReceivedQuantity = BigDecimal.ZERO;
    @Column(name = "accepted_quantity", precision = 22, scale = 7, nullable = false, updatable = false)
    private BigDecimal acceptedQuantity = BigDecimal.ZERO;
    @Column(name = "rejected_quantity", precision = 22, scale = 7, nullable = false, updatable = false)
    private BigDecimal rejectedQuantity = BigDecimal.ZERO;
    @Column(name = "damaged_quantity", precision = 22, scale = 7, nullable = false, updatable = false)
    private BigDecimal damagedQuantity = BigDecimal.ZERO;
    @Column(name = "excess_quantity", precision = 22, scale = 7, nullable = false, updatable = false)
    private BigDecimal excessQuantity = BigDecimal.ZERO;
    @Column(name = "shortage_quantity", precision = 22, scale = 7, nullable = false, updatable = false)
    private BigDecimal shortageQuantity = BigDecimal.ZERO;
    @Column(name = "shortage_reason", columnDefinition = "text", updatable = false)
    private String shortageReason;
    @Column(name = "quality_status", length = 50)
    private String qualityStatus;
    @Column(name = "stock_movement_id", updatable = false)
    private UUID stockMovementId;
    @Column(name = "accreditation_status", length = 50, nullable = false, updatable = false)
    private String accreditationStatus;
    @Column(name = "accreditation_message", columnDefinition = "text", updatable = false)
    private String accreditationMessage;
    @Column(name = "accreditation_exception_id", updatable = false)
    private UUID accreditationExceptionId;
}
