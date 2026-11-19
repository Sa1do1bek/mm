package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Immutable evidence-to-business-line linkage for receiving audit. */
@Getter
@Setter
@Entity
@Table(name = "goods_receipt_evidence")
public class GoodsReceiptEvidence extends BusinessEntity {
    @Column(name = "goods_receipt_id", nullable = false, updatable = false)
    private UUID goodsReceiptId;
    @Column(name = "goods_receipt_line_id", nullable = false, updatable = false)
    private UUID goodsReceiptLineId;
    @Column(name = "attachment_id", nullable = false, updatable = false)
    private UUID attachmentId;
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
    @Column(name = "stock_movement_id", updatable = false)
    private UUID stockMovementId;
}
