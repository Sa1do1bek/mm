package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.enums.MovementType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record StockMovementDocumentSearchRequest(
    String search,
    List<String> status,
    UUID warehouseId,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    boolean includeDeleted,
    UUID referenceId,
    MovementType movementType
) implements SearchCriteria {
    public static StockMovementDocumentSearchRequest empty() {
        return new StockMovementDocumentSearchRequest(
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            null
        );
    }
}
