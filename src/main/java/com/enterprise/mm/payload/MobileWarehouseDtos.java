package com.enterprise.mm.payload;

import com.enterprise.mm.enums.MobileWarehouseOperationType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class MobileWarehouseDtos {
    private MobileWarehouseDtos() {
    }

    public record MobileWarehouseRequest(
        UUID materialId,
        UUID stockBatchId,
        UUID warehouseId,
        UUID targetWarehouseId,
        UUID storageLocationId,
        UUID targetStorageLocationId,
        BigDecimal quantity,
        BigDecimal unitCost,
        String barcodeOrRfid
    ) {
    }

    public record MobileWarehouseOperationDto(
        UUID id,
        MobileWarehouseOperationType operationType,
        String status,
        UUID materialId,
        UUID warehouseId,
        UUID targetWarehouseId,
        UUID storageLocationId,
        UUID targetStorageLocationId,
        BigDecimal quantity,
        BigDecimal unitCost,
        UUID stockMovementId,
        String barcodeOrRfid,
        boolean signed,
        LocalDateTime signedAt
    ) {
    }

    public record MobileSignatureRequest(
        String signaturePayload,
        String pkcs7,
        String certificateSerial,
        String signerTin
    ) {
    }

    public record OfflineOperationRequest(
        String clientOperationId,
        MobileWarehouseOperationType operationType,
        LocalDateTime createdOfflineAt,
        MobileWarehouseRequest payload
    ) {
    }

    public record OfflineSyncRequest(
        String deviceId,
        List<OfflineOperationRequest> operations
    ) {
    }

    public record OfflineSyncResult(
        UUID queueItemId,
        String clientOperationId,
        MobileWarehouseOperationType operationType,
        String status,
        UUID serverOperationId,
        UUID stockMovementId,
        String message,
        boolean duplicate
    ) {
    }

    public record OfflineSyncResponse(
        String deviceId,
        int received,
        int synced,
        int failed,
        List<OfflineSyncResult> results
    ) {
    }
}
