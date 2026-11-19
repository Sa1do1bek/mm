package com.enterprise.mm.payload;

import com.enterprise.mm.enums.MobileWarehouseOperationType;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TsdOfflineOperationCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private String deviceId;
    private String clientOperationId;
    private MobileWarehouseOperationType operationType;
    private String payloadJson;
    private UUID serverOperationId;
    private UUID stockMovementId;
    private int attemptCount;
    private String lastError;
    private LocalDateTime createdOfflineAt;
    private LocalDateTime syncedAt;
}
