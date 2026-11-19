package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QualityQuarantineLotResponse extends BaseRecordDto {
    private UUID transferAllocationId;
    private UUID transferClearanceId;
    private String quarantineNumber;
    private UUID qualityInspectionId;
    private String qualityInspectionCode;
    private String qualityInspectionName;
    private UUID qualityNonconformanceId;
    private String qualityNonconformanceCode;
    private String qualityNonconformanceName;
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
    private String quarantineReason;
    private BigDecimal quarantinedQuantity;
    private BigDecimal releasedQuantity;
    private BigDecimal rejectedQuantity;
    private BigDecimal remainingQuantity;
    private String disposition;
    private UUID responsibleEmployeeId;
    private String responsibleEmployeeCode;
    private String responsibleEmployeeName;
    private LocalDateTime quarantineStartedAt;
    private LocalDateTime releasedAt;
    private LocalDateTime rejectedAt;
    private LocalDateTime closedAt;
}