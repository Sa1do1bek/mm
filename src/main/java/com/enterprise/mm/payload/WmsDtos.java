package com.enterprise.mm.payload;

import com.enterprise.mm.enums.CrossDockingOutboundReferenceType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public final class WmsDtos {
    private WmsDtos() {
    }

    public record CrossDockingActionRequest(
        UUID stockMovementId,
        UUID stockBatchId,
        UUID storageLocationId,
        UUID destinationWarehouseId,
        UUID destinationStorageLocationId,
        CrossDockingOutboundReferenceType outboundReferenceType,
        UUID outboundReferenceId,
        BigDecimal quantity,
        BigDecimal unitCost,
        LocalDateTime plannedShipAt,
        String comment
    ) {
    }

    public record PutawayRecommendationRequest(
        UUID materialId,
        UUID warehouseId,
        BigDecimal quantity,
        String hazardClass,
        String temperatureZone,
        String abcClass,
        Integer shelfLifeDays,
        boolean crossDockAllowed
    ) {
    }

    public record PutawayRecommendationDto(
        UUID ruleId,
        UUID storageLocationId,
        String storageLocationName,
        String storageZoneCode,
        BigDecimal requestedQuantity,
        BigDecimal occupiedQuantity,
        BigDecimal remainingCapacity,
        String reason
    ) {
    }
}
