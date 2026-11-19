package com.enterprise.mm.payload;

import java.util.UUID;

public record FixedAssetSearchRequest(
    UUID materialId,
    UUID equipmentId,
    String lifecycleStatus,
    String custodyKind,
    UUID warehouseId,
    UUID departmentId,
    String search,
    String inventoryNumber,
    String serialNumber,
    String category,
    String depreciationMethod,
    String query
) {
    public FixedAssetSearchRequest(
        UUID materialId,
        UUID equipmentId,
        String lifecycleStatus,
        String custodyKind,
        UUID warehouseId,
        UUID departmentId,
        String inventoryNumber,
        String serialNumber
    ) {
        this(materialId, equipmentId, lifecycleStatus, custodyKind, warehouseId, departmentId, null, inventoryNumber, serialNumber, null, null, null);
    }
}
