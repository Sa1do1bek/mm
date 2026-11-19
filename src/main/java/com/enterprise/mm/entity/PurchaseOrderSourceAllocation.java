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
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
    name = "purchase_order_source_allocations",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_po_source_allocation_source",
        columnNames = {"tenant_id", "company_id", "purchase_order_item_id", "purchase_request_item_id", "rfq_purchase_request_source_id"}
    )
)
public class PurchaseOrderSourceAllocation extends BusinessEntity {
    @Column(name = "purchase_order_item_id", nullable = false)
    private UUID purchaseOrderItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_item_id", insertable = false, updatable = false)
    private PurchaseOrderItem purchaseOrderItem;

    @Column(name = "purchase_request_item_id", nullable = false)
    private UUID purchaseRequestItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_request_item_id", insertable = false, updatable = false)
    private PurchaseRequestItem purchaseRequestItem;

    @Column(name = "rfq_purchase_request_source_id")
    private UUID rfqPurchaseRequestSourceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rfq_purchase_request_source_id", insertable = false, updatable = false)
    private RFQPurchaseRequestSource rfqPurchaseRequestSource;

    @Column(name = "ordered_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal orderedQuantity = BigDecimal.ZERO;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        purchaseOrderItemId = purchaseOrderItem == null ? purchaseOrderItemId : purchaseOrderItem.getId();
        purchaseRequestItemId = purchaseRequestItem == null ? purchaseRequestItemId : purchaseRequestItem.getId();
        rfqPurchaseRequestSourceId = rfqPurchaseRequestSource == null ? rfqPurchaseRequestSourceId : rfqPurchaseRequestSource.getId();
    }
}
