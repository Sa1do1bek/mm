package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.enums.MovementType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record StockMovementSearchRequest(
    String search,
    List<String> status,
    UUID warehouseId,
    List<UUID> materialIds,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    boolean includeDeleted,
    UUID inventoryId,
    UUID referenceId,
    String referenceType,
    MovementType movementType,
    String materialCode,
    String materialName,
    UUID materialId,
    List<UUID> warehouseIds,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate movementDate,
    List<MovementType> movementTypes
) implements SearchCriteria {
    public StockMovementSearchRequest(
        String search,
        List<String> status,
        UUID warehouseId,
        List<UUID> materialIds,
        LocalDate dateFrom,
        LocalDate dateTo,
        boolean includeDeleted,
        UUID inventoryId,
        UUID referenceId,
        String referenceType,
        MovementType movementType
    ) {
        this(
            search,
            status,
            warehouseId,
            materialIds,
            dateFrom,
            dateTo,
            includeDeleted,
            inventoryId,
            referenceId,
            referenceType,
            movementType,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    public static StockMovementSearchRequest empty() {
        return new StockMovementSearchRequest(
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }
}
