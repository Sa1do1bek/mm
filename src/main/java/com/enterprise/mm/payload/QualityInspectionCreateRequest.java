package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QualityInspectionCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private String inspectionNumber;
    private String inspectionType;
    private UUID supplierId;
    private UUID purchaseOrderId;
    private UUID purchaseOrderItemId;
    private UUID stockMovementId;
    private UUID materialId;
    private UUID warehouseId;
    private UUID storageLocationId;
    private UUID stockBatchId;
    private BigDecimal receivedQuantity;
    private BigDecimal sampleQuantity;
    private BigDecimal acceptedQuantity;
    private BigDecimal rejectedQuantity;
    private BigDecimal quarantinedQuantity;
    private LocalDate inspectionDate;
    private LocalDate dueDate;
    private UUID inspectorEmployeeId;
    private UUID qualityPassportAttachmentId;
    private LocalDateTime quarantineStartedAt;
    private LocalDateTime quarantineReleasedAt;
    private LocalDateTime inspectionStartedAt;
    private LocalDateTime completedAt;
    private String conclusion;
}
