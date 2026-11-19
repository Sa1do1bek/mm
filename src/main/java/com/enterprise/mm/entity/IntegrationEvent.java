package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.IntegrationEventDirection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "integration_events", uniqueConstraints = @UniqueConstraint(name = "uk_integration_events_tenant_company_idempotency", columnNames = {"tenant_id", "company_id", "idempotency_key"}))
public class IntegrationEvent extends BusinessEntity {
    @Column(nullable = false, length = 20)
    private String direction = IntegrationEventDirection.OUTBOUND.name();

    @Column(length = 120)
    private String eventType;
    @Column(length = 120)
    private String aggregateType;
    @Column(name = "aggregate_id")
    private UUID aggregateId;
    @Column(columnDefinition = "text")
    private String payload;
    private Integer retryCount = 0;
    private LocalDateTime nextRetryAt;
    private LocalDateTime lastAttemptAt;
    @Column(length = 120)
    private String idempotencyKey;
    @Column(columnDefinition = "text")
    private String lastError;

    @Column(name = "external_id", length = 200)
    private String externalId;

    @Column(name = "replay_of_event_id")
    private UUID replayOfEventId;

    @Column(name = "manual_reason", columnDefinition = "text")
    private String manualReason;
}
