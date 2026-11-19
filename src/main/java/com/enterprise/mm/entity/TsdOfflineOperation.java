package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.MobileWarehouseOperationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "tsd_offline_operations")
public class TsdOfflineOperation extends BusinessEntity {
    @Column(name = "device_id", nullable = false, length = 120)
    private String deviceId;

    @Column(name = "client_operation_id", nullable = false, length = 160)
    private String clientOperationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 40)
    private MobileWarehouseOperationType operationType;

    @Column(name = "payload_json", nullable = false, columnDefinition = "text")
    private String payloadJson;

    @Column(name = "server_operation_id")
    private UUID serverOperationId;

    @Column(name = "stock_movement_id")
    private UUID stockMovementId;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_offline_at")
    private LocalDateTime createdOfflineAt;

    @Column(name = "synced_at")
    private LocalDateTime syncedAt;
}
