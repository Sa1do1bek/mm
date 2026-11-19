package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class WarehouseIdentificationDtos {
    private WarehouseIdentificationDtos() {
    }

    public record IdentificationLookupDto(String entityType, UUID entityId, String code, String name, String identifierType, String identifierValue) {
    }

    public record IdentificationGenerateRequest(String identifierType) {
    }

    public record WarehouseInventoryLookupDto(
        UUID warehouseId,
        String warehouseCode,
        String warehouseName,
        List<WarehouseInventoryMaterialDto> materials,
        List<WarehouseInventoryStorageLocationDto> storageLocations,
        List<WarehouseInventoryBatchDto> batches
    ) {
    }

    public record WarehouseInventoryMaterialDto(
        UUID materialId,
        String materialCode,
        String materialName,
        String materialStatus,
        UUID materialGroupId,
        UUID unitOfMeasureId,
        String barcode,
        String qrCode,
        String rfidTag
    ) {
    }

    public record WarehouseInventoryStorageLocationDto(
        UUID storageLocationId,
        String code,
        String name,
        String status,
        String zoneCode,
        String binCode,
        String barcode,
        String qrCode,
        String rfidTag
    ) {
    }

    public record WarehouseInventoryBatchDto(
        UUID batchId,
        UUID materialId,
        String materialCode,
        String materialName,
        String code,
        String name,
        String status,
        String batchNumber,
        String serialNumber,
        BigDecimal quantity,
        BigDecimal unitCost,
        LocalDate receivedDate,
        LocalDate expirationDate,
        String barcode,
        String qrCode,
        String rfidTag
    ) {
    }
}
