package com.enterprise.mm.payload;

import com.enterprise.mm.enums.MaterialUnitOperationType;
import com.enterprise.mm.enums.MaterialUnitStatus;
import java.util.List;
import java.util.UUID;

public record MaterialUnitSearchRequest(
    String search,
    UUID materialId,
    UUID warehouseId,
    UUID storageLocationId,
    UUID storageBinId,
    UUID stockBatchId,
    List<MaterialUnitStatus> status,
    String sourceReferenceType,
    UUID sourceReferenceId,
    MaterialUnitOperationType sourceOperationType
) {
}
