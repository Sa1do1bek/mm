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
@Table(name = "supplier_evaluations")
public class SupplierEvaluation extends BusinessEntity {
    @Column(name = "supplier_id", nullable = false)
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
    @Column(name = "material_id")
    private UUID materialId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", insertable = false, updatable = false)
    private Material material;
    @Column(name = "ordered_quantity", precision = 22, scale = 7)
    private BigDecimal orderedQuantity = BigDecimal.ZERO;
    @Column(name = "received_quantity", precision = 22, scale = 7)
    private BigDecimal receivedQuantity = BigDecimal.ZERO;
    @Column(name = "accepted_quantity", precision = 22, scale = 7)
    private BigDecimal acceptedQuantity = BigDecimal.ZERO;
    @Column(name = "rejected_quantity", precision = 22, scale = 7)
    private BigDecimal rejectedQuantity = BigDecimal.ZERO;
    @Column(name = "late_days")
    private Integer lateDays = 0;
    @Column(precision = 7, scale = 2)
    private BigDecimal deliveryScore = BigDecimal.ZERO;
    @Column(precision = 7, scale = 2)
    private BigDecimal qualityScore = BigDecimal.ZERO;
    @Column(name = "quantity_score", precision = 7, scale = 2)
    private BigDecimal quantityScore = BigDecimal.ZERO;
    @Column(precision = 7, scale = 2)
    private BigDecimal priceScore = BigDecimal.ZERO;
    @Column(precision = 7, scale = 2)
    private BigDecimal claimsScore = BigDecimal.ZERO;
    @Column(precision = 7, scale = 2)
    private BigDecimal totalScore = BigDecimal.ZERO;
    @Column(name = "evaluation_source", length = 40)
    private String evaluationSource;
    @Column(name = "calculation_details_json", columnDefinition = "text")
    private String calculationDetailsJson;
    private LocalDateTime evaluatedAt;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        supplierId = supplier == null ? supplierId : supplier.getId();
        purchaseOrderId = purchaseOrder == null ? purchaseOrderId : purchaseOrder.getId();
        purchaseOrderItemId = purchaseOrderItem == null ? purchaseOrderItemId : purchaseOrderItem.getId();
        stockMovementId = stockMovement == null ? stockMovementId : stockMovement.getId();
        materialId = material == null ? materialId : material.getId();
    }
}
