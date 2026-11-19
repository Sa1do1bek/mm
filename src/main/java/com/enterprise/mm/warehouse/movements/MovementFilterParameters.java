package com.enterprise.mm.warehouse.movements;

import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.payload.StockMovementSearchRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

/** Single-constructor HTTP binding shared by list and export. */
public record MovementFilterParameters(
    String search,
    List<String> status,
    UUID warehouseId,
    List<UUID> materialIds,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    Boolean includeDeleted,
    UUID inventoryId,
    UUID referenceId,
    String referenceType,
    MovementType movementType,
    String materialCode,
    String materialName,
    UUID materialId,
    List<UUID> warehouseIds,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate movementDate,
    List<MovementType> movementTypes,
    MovementType operationType
) {
    public StockMovementSearchRequest toRequest() {
        return new StockMovementSearchRequest(search,status,warehouseId,materialIds,dateFrom,dateTo,Boolean.TRUE.equals(includeDeleted),
            inventoryId,referenceId,referenceType,movementType != null ? movementType : operationType,
            materialCode,materialName,materialId,warehouseIds,movementDate,movementTypes);
    }
}
