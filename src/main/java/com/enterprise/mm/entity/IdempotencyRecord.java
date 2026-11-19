package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
    name = "idempotency_records",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_idempotency_records_scope",
        columnNames = {"tenant_id", "company_id", "operation", "resource_id", "idempotency_key"}
    )
)
public class IdempotencyRecord extends BusinessEntity {
    @Column(nullable = false, length = 100)
    private String operation;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Column(name = "idempotency_key", nullable = false, length = 160)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_json", nullable = false, columnDefinition = "text")
    private String responseJson;
}
