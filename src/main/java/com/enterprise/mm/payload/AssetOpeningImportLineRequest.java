package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.util.UUID;

public record AssetOpeningImportLineRequest(
    UUID materialId,
    UUID equipmentId,
    String inventoryNumber,
    String serialNumber,
    String lifecycleStatus,
    String custodyKind,
    UUID warehouseId,
    UUID departmentId,
    BigDecimal acquisitionValue
) {
}
