package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "supplier_activation_records")
public class SupplierActivationRecord extends BusinessEntity {
    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Column(name = "activated_by", nullable = false, length = 120)
    private String activatedBy;

    @Column(name = "activated_at", nullable = false)
    private LocalDateTime activatedAt;

    @Column(name = "activation_comment", nullable = false, columnDefinition = "text")
    private String activationComment;

    @Column(name = "readiness_snapshot_json", nullable = false, columnDefinition = "text")
    private String readinessSnapshotJson;

    @Column(name = "policy_version", nullable = false, length = 40)
    private String policyVersion;
}
