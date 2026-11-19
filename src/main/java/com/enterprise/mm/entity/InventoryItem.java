package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.InventoryDiscrepancyDecisionType;
import com.enterprise.mm.enums.InventoryDiscrepancyType;
import com.enterprise.mm.enums.InventoryExplanationStatus;
import com.enterprise.mm.enums.InventoryLineStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "inventory_items")
public class InventoryItem extends BusinessEntity {
    @Column(name = "inventory_document_id", nullable = false)
    private UUID inventoryDocumentId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inventory_document_id", insertable = false, updatable = false)
    private InventoryDocument inventoryDocument;
    @Column(nullable=false) private long countRevision;
    private Long decisionCountRevision;
    private UUID reconciledSourceMovementId;
    @Column(length=1000) private String countComment;
    @Column(nullable=false) private boolean foundStock;
    @Column(length=1000) private String foundReason;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(columnDefinition="jsonb", nullable=false) private java.util.List<UUID> foundEvidenceIds = new java.util.ArrayList<>();
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(columnDefinition="jsonb", nullable=false) private java.util.List<UUID> decisionEvidenceIds = new java.util.ArrayList<>();
    @Column(length=120) private String observedBatchNumber;
    @Column(length=120) private String observedSerialNumber;
    private java.time.LocalDate observedExpiryDate;
    @Column(length=1000) private String valuationBasis;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(columnDefinition="jsonb", nullable=false) private java.util.List<UUID> valuationEvidenceIds = new java.util.ArrayList<>();
    private Long valuationCountRevision;
    @Column(length=120) private String valuedBy;
    private LocalDateTime valuedAt;
    @Column(length=100) private String reviewBlocker;
    private UUID sourceCorrectionMovementId;
    @Column(name = "material_id", nullable = false)
    private UUID materialId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", insertable = false, updatable = false)
    private Material material;
    @Column(precision = 22, scale = 7)
    private BigDecimal systemQuantity = BigDecimal.ZERO;
    @Column(name = "as_of_quantity", precision = 22, scale = 7)
    private BigDecimal asOfQuantity;
    @Column(name = "as_of_value", precision = 19, scale = 4)
    private BigDecimal asOfValue;
    @Column(precision = 22, scale = 7)
    private BigDecimal countedQuantity;
    @Column(precision = 22, scale = 7)
    private BigDecimal differenceQuantity = BigDecimal.ZERO;
    @Column(name = "warehouse_id")
    private UUID warehouseId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", insertable = false, updatable = false)
    private Warehouse warehouse;
    @Column(name = "storage_location_id")
    private UUID storageLocationId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "storage_location_id", insertable = false, updatable = false)
    private StorageLocation storageLocation;
    @Column(name = "storage_bin_id")
    private UUID storageBinId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "storage_bin_id", insertable = false, updatable = false)
    private StorageBin storageBin;
    @Column(name = "stock_batch_id")
    private UUID stockBatchId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_batch_id", insertable = false, updatable = false)
    private StockBatch stockBatch;
    @Column(precision = 19, scale = 4)
    private BigDecimal unitCost = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal differenceAmount = BigDecimal.ZERO;
    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private InventoryLineStatus lineStatus = InventoryLineStatus.NOT_COUNTED;
    @Enumerated(EnumType.STRING)
    @Column(length = 60)
    private InventoryDiscrepancyType discrepancyType;
    @Column(length = 500)
    private String discrepancyReason;
    @Enumerated(EnumType.STRING)
    @Column(name = "decision_type", length = 80)
    private InventoryDiscrepancyDecisionType decisionType;
    @Column(name = "responsible_user_id")
    private UUID responsibleUserId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "responsible_user_id", insertable = false, updatable = false)
    private User responsibleUser;
    @Column(name = "linked_stock_movement_id")
    private UUID linkedStockMovementId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_stock_movement_id", insertable = false, updatable = false)
    private StockMovement linkedStockMovement;
    @Column(name = "linked_purchase_order_id")
    private UUID linkedPurchaseOrderId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_purchase_order_id", insertable = false, updatable = false)
    private PurchaseOrder linkedPurchaseOrder;
    @Column(name = "linked_supplier_id")
    private UUID linkedSupplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_supplier_id", insertable = false, updatable = false)
    private Supplier linkedSupplier;
    @Column(name = "linked_contract_id")
    private UUID linkedContractId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_contract_id", insertable = false, updatable = false)
    private Contract linkedContract;
    @Column(name = "compensation_amount", precision = 19, scale = 4)
    private BigDecimal compensationAmount;
    @Column(name = "decision_comment", length = 1000)
    private String decisionComment;
    @Column(name = "decided_by", length = 120)
    private String decidedBy;
    @Column(name = "decided_at")
    private LocalDateTime decidedAt;
    @Column(length = 1000)
    private String commentText;
    @Column(length = 120)
    private String countedBy;
    private LocalDateTime countedAt;
    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private InventoryExplanationStatus explanationStatus = InventoryExplanationStatus.NOT_REQUIRED;
    @Column(length = 120)
    private String explanationRequestedFrom;
    private LocalDateTime explanationRequestedAt;
    @Column(length = 1000)
    private String explanationText;
    private LocalDateTime explanationProvidedAt;
    @Column(length = 500)
    private String explanationReviewComment;
    private LocalDateTime recountRequestedAt;
    @Column(length = 500)
    private String recountReason;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        if (inventoryDocument != null) {
            inventoryDocumentId = inventoryDocument.getId();
        }
        if (material != null) {
            materialId = material.getId();
        }
        if (warehouse != null) {
            warehouseId = warehouse.getId();
        }
        if (storageLocation != null) {
            storageLocationId = storageLocation.getId();
        }
        if (storageBin != null) {
            storageBinId = storageBin.getId();
        }
        if (stockBatch != null) {
            stockBatchId = stockBatch.getId();
        }
        if (responsibleUser != null) {
            responsibleUserId = responsibleUser.getId();
        }
        if (linkedStockMovement != null) {
            linkedStockMovementId = linkedStockMovement.getId();
        }
        if (linkedPurchaseOrder != null) {
            linkedPurchaseOrderId = linkedPurchaseOrder.getId();
        }
        if (linkedSupplier != null) {
            linkedSupplierId = linkedSupplier.getId();
        }
        if (linkedContract != null) {
            linkedContractId = linkedContract.getId();
        }
    }
}
