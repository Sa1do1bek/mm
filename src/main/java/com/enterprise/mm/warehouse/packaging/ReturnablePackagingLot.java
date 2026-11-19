package com.enterprise.mm.warehouse.packaging;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @Entity
@Table(name = "returnable_packaging_lots")
public class ReturnablePackagingLot extends BusinessEntity {
    @Column(name = "supplier_id") private UUID supplierId;
    @Column(name = "ownership", nullable = false, length = 30) private String ownership;
    @Column(name = "account_code", nullable = false, length = 30) private String accountCode;
    @Column(name = "unit_cost", nullable = false, precision = 19, scale = 4) private BigDecimal unitCost = BigDecimal.ZERO;
    @Column(name = "material_id", nullable = false) private UUID materialId;
    @Column(name = "content_material_id") private UUID contentMaterialId;
    @Column(name = "source_type", nullable = false, length = 30) private String sourceType = "PURCHASE_ORDER";
    @Column(name = "purchase_order_item_id") private UUID purchaseOrderItemId;
    @Column(name = "goods_receipt_id") private UUID goodsReceiptId;
    @Column(name = "goods_receipt_line_id") private UUID goodsReceiptLineId;
    @Column(name = "manual_document_id") private UUID manualDocumentId;
    @Column(name = "manual_content_movement_id") private UUID manualContentMovementId;
    @Column(name = "due_date") private LocalDate dueDate;
    @Column(name = "receipt_stock_movement_id") private UUID receiptStockMovementId;
    @Column(name = "receipt_posting_batch_id") private UUID receiptPostingBatchId;
    @Column(name = "warehouse_id", nullable = false) private UUID warehouseId;
    @Column(name = "storage_location_id") private UUID storageLocationId;
    @Column(name = "storage_bin_id") private UUID storageBinId;
    @Column(name = "stock_batch_id") private UUID stockBatchId;
    @Column(name = "legacy_stock_balance_id") private UUID legacyStockBalanceId;
    @Column(name = "opening_value", precision = 19, scale = 4) private BigDecimal openingValue;
    @Column(name = "unknown_quantity", nullable = false, precision = 22, scale = 7) private BigDecimal unknownQuantity = BigDecimal.ZERO;
    @Column(name = "received_quantity", nullable = false, precision = 22, scale = 7) private BigDecimal receivedQuantity;
    @Column(name = "filled_quantity", nullable = false, precision = 22, scale = 7) private BigDecimal filledQuantity;
    @Column(name = "partial_quantity", nullable = false, precision = 22, scale = 7) private BigDecimal partialQuantity = BigDecimal.ZERO;
    @Column(name = "empty_quantity", nullable = false, precision = 22, scale = 7) private BigDecimal emptyQuantity = BigDecimal.ZERO;
    @Column(name = "damaged_quantity", nullable = false, precision = 22, scale = 7) private BigDecimal damagedQuantity = BigDecimal.ZERO;
    @Column(name = "reserved_quantity", nullable = false, precision = 22, scale = 7) private BigDecimal reservedQuantity = BigDecimal.ZERO;
    @Column(name = "dispatched_quantity", nullable = false, precision = 22, scale = 7) private BigDecimal dispatchedQuantity = BigDecimal.ZERO;
    @Column(name = "confirmed_quantity", nullable = false, precision = 22, scale = 7) private BigDecimal confirmedQuantity = BigDecimal.ZERO;
}
