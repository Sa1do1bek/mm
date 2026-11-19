package com.enterprise.mm.payload;

import com.enterprise.mm.enums.CrossDockingOutboundReferenceType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CrossDockingTaskResponse extends BaseRecordDto {
    private String taskNumber;
    private UUID supplierId;
    private UUID purchaseOrderId;
    private UUID inboundStockMovementId;
    private CrossDockingOutboundReferenceType outboundReferenceType;
    private UUID outboundReferenceId;
    private UUID materialId;
    private UUID stockBatchId;
    private UUID warehouseId;
    private UUID stagingLocationId;
    private UUID destinationWarehouseId;
    private UUID destinationStorageLocationId;
    private BigDecimal quantity;
    private BigDecimal unitCost;
    private UUID outboundStockMovementId;
    private LocalDateTime plannedShipAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
