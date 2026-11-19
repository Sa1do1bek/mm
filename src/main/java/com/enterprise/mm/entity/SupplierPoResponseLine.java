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
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Per-{@link PurchaseOrderItem} decision within a {@link SupplierPoResponse}. */
@Getter
@Setter
@Entity
@Table(name = "supplier_po_response_lines")
public class SupplierPoResponseLine extends BusinessEntity {
    @Column(name = "response_id", nullable = false)
    private UUID responseId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "response_id", insertable = false, updatable = false)
    private SupplierPoResponse response;

    @Column(name = "purchase_order_item_id", nullable = false)
    private UUID purchaseOrderItemId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_item_id", insertable = false, updatable = false)
    private PurchaseOrderItem purchaseOrderItem;

    @Column(name = "line_snapshot_json", nullable = false, columnDefinition = "text")
    private String lineSnapshotJson;

    @Column(name = "decision", nullable = false, length = 50)
    private String decision;

    @Column(name = "ordered_quantity", precision = 22, scale = 7)
    private BigDecimal orderedQuantity = BigDecimal.ZERO;

    @Column(name = "open_quantity_at_dispatch", precision = 22, scale = 7)
    private BigDecimal openQuantityAtDispatch = BigDecimal.ZERO;

    @Column(name = "accepted_quantity", precision = 22, scale = 7)
    private BigDecimal acceptedQuantity = BigDecimal.ZERO;

    @Column(name = "unconfirmed_quantity", precision = 22, scale = 7)
    private BigDecimal unconfirmedQuantity = BigDecimal.ZERO;

    @Column(name = "reason_code", length = 80)
    private String reasonCode;

    @Column(name = "supplier_comment", columnDefinition = "text")
    private String supplierComment;

    @PrePersist
    @PreUpdate
    void syncRelations() {
        responseId = response == null ? responseId : response.getId();
        purchaseOrderItemId = purchaseOrderItem == null ? purchaseOrderItemId : purchaseOrderItem.getId();
    }
}
