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
@Table(name = "advance_shipping_notice_items")
public class AdvanceShippingNoticeItem extends BusinessEntity {
    @Column(name = "asn_id", nullable = false)
    private UUID asnId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asn_id", insertable = false, updatable = false)
    private AdvanceShippingNotice asn;

    @Column(name = "purchase_order_item_id")
    private UUID purchaseOrderItemId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_item_id", insertable = false, updatable = false)
    private PurchaseOrderItem purchaseOrderItem;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", insertable = false, updatable = false)
    private Material material;

    @Column(precision = 22, scale = 7)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(name = "received_quantity", precision = 22, scale = 7)
    private BigDecimal receivedQuantity = BigDecimal.ZERO;

    @Column(name = "cancelled_quantity", precision = 22, scale = 7)
    private BigDecimal cancelledQuantity = BigDecimal.ZERO;

    @Column(name = "package_count", precision = 19, scale = 4)
    private BigDecimal packageCount = BigDecimal.ZERO;

    @Column(name = "package_type", length = 80)
    private String packageType;

    @Column(name = "gross_weight", precision = 19, scale = 4)
    private BigDecimal grossWeight = BigDecimal.ZERO;

    @Column(name = "net_weight", precision = 19, scale = 4)
    private BigDecimal netWeight = BigDecimal.ZERO;

    @Column(name = "batch_number", length = 120)
    private String batchNumber;

    @Column(name = "serial_numbers", columnDefinition = "text")
    private String serialNumbers;

    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    @Column(name = "supplier_po_commitment_id")
    private UUID supplierPoCommitmentId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_po_commitment_id", insertable = false, updatable = false)
    private SupplierPoCommitment supplierPoCommitment;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        asnId = asn == null ? asnId : asn.getId();
        purchaseOrderItemId = purchaseOrderItem == null ? purchaseOrderItemId : purchaseOrderItem.getId();
        materialId = material == null ? materialId : material.getId();
        supplierPoCommitmentId = supplierPoCommitment == null ? supplierPoCommitmentId : supplierPoCommitment.getId();
    }
}
