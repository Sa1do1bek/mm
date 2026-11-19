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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * One immutable dispatch of a single approved {@link PurchaseOrder} version to the
 * supplier portal. A new row is created only when the PO's commercial content
 * actually changed since the last dispatch (see {@code purchase_order_version} +
 * the DB unique constraint on (purchase_order_id, purchase_order_version)) -- a
 * same-version resend reuses the existing row.
 */
@Getter
@Setter
@Entity
@Table(name = "supplier_po_dispatches")
public class SupplierPoDispatch extends BusinessEntity {
    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", insertable = false, updatable = false)
    private PurchaseOrder purchaseOrder;

    @Column(name = "purchase_order_version", nullable = false)
    private Long purchaseOrderVersion;

    @Column(name = "purchase_order_revision_id")
    private UUID purchaseOrderRevisionId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_revision_id", insertable = false, updatable = false)
    private PurchaseOrderRevision purchaseOrderRevision;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "portal_account_id")
    private UUID portalAccountId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "portal_account_id", insertable = false, updatable = false)
    private SupplierPortalAccount portalAccount;

    @Column(name = "recipient_email", length = 320)
    private String recipientEmail;

    @Column(name = "dispatch_status", length = 50)
    private String dispatchStatus;

    @Column(name = "response_due_date")
    private LocalDate responseDueDate;

    @Column(name = "queued_at")
    private LocalDateTime queuedAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "viewed_at")
    private LocalDateTime viewedAt;

    @Column(name = "superseded_at")
    private LocalDateTime supersededAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "snapshot_json", nullable = false, columnDefinition = "text")
    private String snapshotJson;

    @Column(name = "snapshot_hash", nullable = false, length = 64)
    private String snapshotHash;

    @Column(name = "provider_message_id", length = 200)
    private String providerMessageId;

    @Column(name = "delivery_attempts", nullable = false)
    private int deliveryAttempts = 0;

    @Column(name = "last_delivery_error", columnDefinition = "text")
    private String lastDeliveryError;

    @Column(name = "is_current", nullable = false)
    private boolean current = true;

    /** Reminder-dedup timestamps (V217) -- set once a "response due soon" / "response
     * overdue" notification has fired for the current response window, so the
     * {@code SupplierPoReminderScheduler} does not re-notify on every tick. */
    @Column(name = "due_soon_reminder_sent_at")
    private LocalDateTime dueSoonReminderSentAt;

    @Column(name = "overdue_reminder_sent_at")
    private LocalDateTime overdueReminderSentAt;

    @PrePersist
    @PreUpdate
    void syncRelations() {
        purchaseOrderId = purchaseOrder == null ? purchaseOrderId : purchaseOrder.getId();
        purchaseOrderRevisionId = purchaseOrderRevision == null ? purchaseOrderRevisionId : purchaseOrderRevision.getId();
        supplierId = supplier == null ? supplierId : supplier.getId();
        portalAccountId = portalAccount == null ? portalAccountId : portalAccount.getId();
    }
}
