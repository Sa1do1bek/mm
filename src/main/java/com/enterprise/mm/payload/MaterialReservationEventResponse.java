package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record MaterialReservationEventResponse(
    UUID id,
    long sequenceNo,
    String eventType,
    LocalDateTime createdAt,
    String createdBy,
    UUID stockBalanceId,
    UUID materialId,
    UUID warehouseId,
    UUID storageLocationId,
    UUID storageBinId,
    UUID stockBatchId,
    String dispositionSnapshot,
    UUID uomIdSnapshot,
    String uomCodeSnapshot,
    BigDecimal quantity,
    BigDecimal onHandDelta,
    BigDecimal availableDelta,
    BigDecimal reservedDelta,
    BigDecimal totalValueDelta,
    String statusFrom,
    String statusTo,
    String reason,
    String evidenceReference,
    long reservationVersion
, String uomShortNameSnapshot) {
        public MaterialReservationEventResponse(UUID id,
    long sequenceNo,
    String eventType,
    LocalDateTime createdAt,
    String createdBy,
    UUID stockBalanceId,
    UUID materialId,
    UUID warehouseId,
    UUID storageLocationId,
    UUID storageBinId,
    UUID stockBatchId,
    String dispositionSnapshot,
    UUID uomIdSnapshot,
    String uomCodeSnapshot,
    BigDecimal quantity,
    BigDecimal onHandDelta,
    BigDecimal availableDelta,
    BigDecimal reservedDelta,
    BigDecimal totalValueDelta,
    String statusFrom,
    String statusTo,
    String reason,
    String evidenceReference,
    long reservationVersion) {
            this(id, sequenceNo, eventType, createdAt, createdBy, stockBalanceId, materialId, warehouseId, storageLocationId, storageBinId, stockBatchId, dispositionSnapshot, uomIdSnapshot, uomCodeSnapshot, quantity, onHandDelta, availableDelta, reservedDelta, totalValueDelta, statusFrom, statusTo, reason, evidenceReference, reservationVersion, null);
        }
        public MaterialReservationEventResponse withUnitShortName(String value) {
            return new MaterialReservationEventResponse(id, sequenceNo, eventType, createdAt, createdBy, stockBalanceId, materialId, warehouseId, storageLocationId, storageBinId, stockBatchId, dispositionSnapshot, uomIdSnapshot, uomCodeSnapshot, quantity, onHandDelta, availableDelta, reservedDelta, totalValueDelta, statusFrom, statusTo, reason, evidenceReference, reservationVersion, value);
        }

}
