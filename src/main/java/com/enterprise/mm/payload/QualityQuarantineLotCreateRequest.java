package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QualityQuarantineLotCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private String quarantineNumber;
    private UUID qualityInspectionId;
    private UUID qualityNonconformanceId;
    private UUID supplierId;
    private UUID purchaseOrderId;
    private UUID purchaseOrderItemId;
    private UUID stockMovementId;
    private UUID materialId;
    private UUID warehouseId;
    private UUID storageLocationId;
    private UUID stockBatchId;
    private String quarantineReason;
    private BigDecimal quarantinedQuantity;
    private BigDecimal releasedQuantity;
    private BigDecimal rejectedQuantity;
    private BigDecimal remainingQuantity;
    private String disposition;
    private UUID responsibleEmployeeId;
    private LocalDateTime quarantineStartedAt;
    private LocalDateTime releasedAt;
    private LocalDateTime rejectedAt;
    private LocalDateTime closedAt;
}
