package com.enterprise.mm.payload;

import com.enterprise.mm.enums.MobileWarehouseOperationType;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TsdOfflineOperationResponse extends BaseRecordDto {
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
