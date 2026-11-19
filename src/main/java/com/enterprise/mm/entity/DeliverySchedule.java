package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
@Table(name = "delivery_schedules")
public class DeliverySchedule extends BusinessEntity {
    @Column(name = "purchase_order_id")
    private UUID purchaseOrderId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", insertable = false, updatable = false)
    private PurchaseOrder purchaseOrder;
    @Column(name = "contract_id")
    private UUID contractId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", insertable = false, updatable = false)
    private Contract contract;
    private LocalDate scheduleDate;
    @Column(precision = 22, scale = 7)
    private BigDecimal plannedQuantity = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal plannedAmount = BigDecimal.ZERO;
    @Column(precision = 22, scale = 7)
    private BigDecimal receivedQuantity = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal receivedAmount = BigDecimal.ZERO;
    private boolean overdueFlag;
    private LocalDateTime controlledAt;
    @Column(name = "business_impact_reference", length = 160)
    private String businessImpactReference;
    @Column(name = "business_impact_comment", columnDefinition = "text")
    private String businessImpactComment;
    @Column(name = "attributable_share", precision = 9, scale = 6)
    private BigDecimal attributableShare;
    @Column(name = "impact_currency_code", length = 3)
    private String impactCurrencyCode;

    @Column(name = "purchase_order_item_id")
    private UUID purchaseOrderItemId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_item_id", insertable = false, updatable = false)
    private PurchaseOrderItem purchaseOrderItem;

    @Column(name = "supplier_po_commitment_id")
    private UUID supplierPoCommitmentId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_po_commitment_id", insertable = false, updatable = false)
    private SupplierPoCommitment supplierPoCommitment;

    /** MANUAL, SUPPLIER_COMMITMENT, or IMPORT. Defaults to MANUAL for legacy rows. */
    @Column(length = 30)
    private String source = "MANUAL";
}
