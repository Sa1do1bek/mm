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
@Table(name = "rfq_delivery_attempts")
public class RfqDeliveryAttempt extends BusinessEntity {
    @Column(name = "delivery_id", nullable = false)
    private UUID deliveryId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_id", insertable = false, updatable = false)
    private RfqInvitationDelivery delivery;

    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Column(name = "idempotency_key", nullable = false, length = 180)
    private String idempotencyKey;

    @Column(name = "attempted_at", nullable = false)
    private LocalDateTime attemptedAt;

    @Column(name = "recipient_email", nullable = false, length = 240)
    private String recipientEmail;

    @Column(name = "provider_message_id", length = 240)
    private String providerMessageId;

    @Column(name = "error_category", length = 80)
    private String errorCategory;

    @Column(name = "error_message", length = 500)
    private String errorMessage;
}
