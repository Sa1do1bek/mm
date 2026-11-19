package com.enterprise.mm.payload;

import com.enterprise.mm.enums.MaterialUnitStatus;
import java.time.LocalDateTime;
import java.util.UUID;

public record MaterialUnitResponse(
    UUID id,
    UUID materialId,
    String manufacturerSerialNumber,
    String internalUnitNumber,
    MaterialUnitStatus unitStatus,
    UUID warehouseId,
    String warehouseName,
    UUID storageLocationId,
    String storageLocationName,
    UUID storageBinId,
    String storageBinName,
    UUID stockBatchId,
    String batchNumber,
    LocalDateTime receivedAt
) {
}
