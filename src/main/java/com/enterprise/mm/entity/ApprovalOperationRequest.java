package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "approval_operation_requests")
public class ApprovalOperationRequest extends BusinessEntity {
    @Column(name = "module_key", nullable = false, length = 80)
    private String moduleKey;

    @Column(name = "target_entity_id", nullable = false)
    private UUID targetEntityId;

    @Column(name = "target_version")
    private Long targetVersion;

    @Column(name = "requester_username", nullable = false, length = 120)
    private String requesterUsername;

    @Column(name = "payload_json", nullable = false, columnDefinition = "text")
    private String payloadJson;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "idempotency_key", nullable = false, length = 160)
    private String idempotencyKey;

    @Column(name = "approval_instance_id")
    private UUID approvalInstanceId;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;
}
