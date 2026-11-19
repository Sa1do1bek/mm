package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record InventoryFxDifferenceSearchRequest(
    String search,
    List<String> status,
    UUID warehouseId,
    UUID supplierId,
    List<UUID> materialIds,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    boolean includeDeleted,
    UUID referenceId
) implements SearchCriteria {
    public static InventoryFxDifferenceSearchRequest empty() {
        return new InventoryFxDifferenceSearchRequest(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            null
        );
    }
}
