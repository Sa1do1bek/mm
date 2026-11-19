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

/**
 * One proposed (and, once accepted by the buyer, confirmed) quantity/date
 * commitment against a {@link SupplierPoResponseLine}. A response line may carry
 * more than one commitment (partial/split delivery proposals).
 */
@Getter
@Setter
@Entity
@Table(name = "supplier_po_commitments")
public class SupplierPoCommitment extends BusinessEntity {
    @Column(name = "response_line_id", nullable = false)
    private UUID responseLineId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "response_line_id", insertable = false, updatable = false)
    private SupplierPoResponseLine responseLine;

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", insertable = false, updatable = false)
    private PurchaseOrder purchaseOrder;

    @Column(name = "purchase_order_item_id", nullable = false)
    private UUID purchaseOrderItemId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_item_id", insertable = false, updatable = false)
    private PurchaseOrderItem purchaseOrderItem;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "proposed_quantity", precision = 22, scale = 7)
    private BigDecimal proposedQuantity = BigDecimal.ZERO;

    @Column(name = "proposed_date")
    private LocalDate proposedDate;

    @Column(name = "confirmed_quantity", precision = 22, scale = 7)
    private BigDecimal confirmedQuantity;

    @Column(name = "confirmed_date")
    private LocalDate confirmedDate;

    @Column(name = "delivery_schedule_id")
    private UUID deliveryScheduleId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_schedule_id", insertable = false, updatable = false)
    private DeliverySchedule deliverySchedule;

    @Column(name = "confirmed_by", length = 120)
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "superseded_commitment_id")
    private UUID supersededCommitmentId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "superseded_commitment_id", insertable = false, updatable = false)
    private SupplierPoCommitment supersededCommitment;

    @Column(name = "is_current", nullable = false)
    private boolean current = true;

    /** Reminder-dedup timestamps (V217) -- see {@code SupplierPoReminderScheduler}. */
    @Column(name = "due_soon_reminder_sent_at")
    private LocalDateTime dueSoonReminderSentAt;

    @Column(name = "overdue_reminder_sent_at")
    private LocalDateTime overdueReminderSentAt;

    @Column(name = "asn_missing_reminder_sent_at")
    private LocalDateTime asnMissingReminderSentAt;

    @PrePersist
    @PreUpdate
    void syncRelations() {
        responseLineId = responseLine == null ? responseLineId : responseLine.getId();
        purchaseOrderId = purchaseOrder == null ? purchaseOrderId : purchaseOrder.getId();
        purchaseOrderItemId = purchaseOrderItem == null ? purchaseOrderItemId : purchaseOrderItem.getId();
        supplierId = supplier == null ? supplierId : supplier.getId();
        deliveryScheduleId = deliverySchedule == null ? deliveryScheduleId : deliverySchedule.getId();
        supersededCommitmentId = supersededCommitment == null ? supersededCommitmentId : supersededCommitment.getId();
    }
}
