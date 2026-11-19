package com.enterprise.mm.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record InventoryUnidentifiedObservation(UUID id, UUID materialMasterRequestId, String observedName,
    UUID warehouseId, UUID storageLocationId, UUID storageBinId, BigDecimal countedQuantity,
    String reason, List<UUID> evidenceIds, String recordedBy, LocalDateTime recordedAt, UUID resolvedLineId) {
    public InventoryUnidentifiedObservation resolve(UUID lineId) {
        return new InventoryUnidentifiedObservation(id, materialMasterRequestId, observedName, warehouseId,
            storageLocationId, storageBinId, countedQuantity, reason, evidenceIds, recordedBy, recordedAt, lineId);
    }
}
