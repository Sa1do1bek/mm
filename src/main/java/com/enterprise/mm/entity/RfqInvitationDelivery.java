package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.RfqDeliveryStatus;
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
@Table(name = "rfq_invitation_deliveries")
public class RfqInvitationDelivery extends BusinessEntity {
    @Column(name = "invitation_id", nullable = false)
    private UUID invitationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invitation_id", insertable = false, updatable = false)
    private RfqSupplierInvitation invitation;

    @Column(name = "published_version_id", nullable = false)
    private UUID publishedVersionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "published_version_id", insertable = false, updatable = false)
    private RfqPublishedVersion publishedVersion;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "recipient_email", nullable = false, length = 240)
    private String recipientEmail;

    @Column(name = "recipient_name", length = 240)
    private String recipientName;

    @Column(name = "provider_message_id", length = 240)
    private String providerMessageId;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "first_sent_at")
    private LocalDateTime firstSentAt;

    @Column(name = "last_sent_at")
    private LocalDateTime lastSentAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "portal_opened_at")
    private LocalDateTime portalOpenedAt;

    @Column(name = "declined_at")
    private LocalDateTime declinedAt;

    @Column(name = "quotation_received_at")
    private LocalDateTime quotationReceivedAt;

    @Column(name = "last_error_category", length = 80)
    private String lastErrorCategory;

    @Column(name = "delivery_purpose", nullable = false, length = 50)
    private String deliveryPurpose = "FULL_RFQ_PACKAGE";

    public RfqDeliveryStatus deliveryStatus() {
        return getStatus() == null ? null : RfqDeliveryStatus.valueOf(getStatus());
    }

    public void setDeliveryStatus(RfqDeliveryStatus status) {
        setStatus(status == null ? null : status.name());
    }
}
