package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "supplier_po_reconfirmation_notifications")
public class SupplierPoReconfirmationNotification extends BusinessEntity {
    @Column(name = "event_type", nullable = false, length = 120)
    private String eventType;

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", insertable = false, updatable = false)
    private PurchaseOrder purchaseOrder;

    @Column(name = "purchase_order_item_id", nullable = false)
    private UUID purchaseOrderItemId;

    @Column(name = "commitment_id", nullable = false)
    private UUID commitmentId;

    @Column(name = "reconfirmation_response_id", nullable = false)
    private UUID reconfirmationResponseId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reconfirmation_response_id", insertable = false, updatable = false)
    private SupplierPoResponse reconfirmationResponse;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Column(name = "portal_account_id")
    private UUID portalAccountId;

    @Column(name = "recipient_email", nullable = false, length = 320)
    private String recipientEmail;

    @Column(name = "po_code", length = 120)
    private String poCode;

    @Column(name = "line_name", columnDefinition = "text")
    private String lineName;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Column(name = "requested_by", length = 120)
    private String requestedBy;

    @Column(name = "provider_message_id", length = 200)
    private String providerMessageId;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "queued_at")
    private LocalDateTime queuedAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;
}
