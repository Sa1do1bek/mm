package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
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
@Table(
    name = "external_inbound_records",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_external_inbound_record_key",
        columnNames = {"tenant_id", "company_id", "source_system", "dataset", "external_id"}
    )
)
public class ExternalInboundRecord extends BusinessEntity {
    @Column(name = "source_system", nullable = false, length = 80)
    private String sourceSystem;

    @Column(nullable = false, length = 120)
    private String dataset;

    @Column(name = "external_id", nullable = false, length = 180)
    private String externalId;

    @Column(name = "external_code", length = 180)
    private String externalCode;

    @Column(name = "provider_status", length = 80)
    private String providerStatus;

    @Column(name = "source_path", length = 500)
    private String sourcePath;

    @Column(name = "payload_hash", length = 64)
    private String payloadHash;

    @Column(columnDefinition = "text")
    private String payload;

    @Column(name = "applied_entity_type", length = 120)
    private String appliedEntityType;

    @Column(name = "applied_entity_id")
    private UUID appliedEntityId;

    @Column(name = "synced_at")
    private LocalDateTime syncedAt;
}