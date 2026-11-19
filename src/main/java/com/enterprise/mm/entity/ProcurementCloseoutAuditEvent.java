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
    name = "procurement_closeout_audit_events",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_procurement_closeout_audit_idempotency",
        columnNames = {"tenant_id", "company_id", "idempotency_key"}
    )
)
public class ProcurementCloseoutAuditEvent extends BusinessEntity {
    @Column(name = "closeout_id", nullable = false)
    private UUID closeoutId;

    @Column(name = "action_code", nullable = false, length = 80)
    private String actionCode;

    @Column(name = "actor", length = 120)
    private String actor;

    @Column(name = "event_at", nullable = false)
    private LocalDateTime eventAt = LocalDateTime.now();

    @Column(name = "idempotency_key", nullable = false, length = 220)
    private String idempotencyKey;

    @Column(name = "snapshot_json", nullable = false, columnDefinition = "text")
    private String snapshotJson;
}
