package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QualityInspectionResponse extends BaseRecordDto {
    private String inspectionNumber;
    private String inspectionType;
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    private UUID purchaseOrderId;
    private String purchaseOrderCode;
    private String purchaseOrderName;
    private UUID purchaseOrderItemId;
    private String purchaseOrderItemCode;
    private String purchaseOrderItemName;
    private UUID stockMovementId;
    private String stockMovementCode;
    private String stockMovementName;
    private UUID materialId;
    private String materialCode;
    private String materialName;
    private UUID warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private UUID storageLocationId;
    private String storageLocationCode;
    private String storageLocationName;
    private UUID stockBatchId;
    private String stockBatchCode;
    private String stockBatchName;
    private BigDecimal receivedQuantity;
    private BigDecimal sampleQuantity;
    private BigDecimal acceptedQuantity;
    private BigDecimal rejectedQuantity;
    private BigDecimal quarantinedQuantity;
    private LocalDate inspectionDate;
    private LocalDate dueDate;
    private UUID inspectorEmployeeId;
    private String inspectorEmployeeCode;
    private String inspectorEmployeeName;
    private UUID qualityPassportAttachmentId;
    private LocalDateTime quarantineStartedAt;
    private LocalDateTime quarantineReleasedAt;
    private LocalDateTime inspectionStartedAt;
    private LocalDateTime completedAt;
    private String conclusion;
}