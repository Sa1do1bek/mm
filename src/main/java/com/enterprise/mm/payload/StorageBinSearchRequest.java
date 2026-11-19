package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.enums.StorageZoneType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record StorageBinSearchRequest(
    String search,
    List<String> status,
    UUID warehouseId,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    boolean includeDeleted,
    UUID storageLocationId,
    StorageZoneType storageZoneType
) implements SearchCriteria {
    public static StorageBinSearchRequest empty() {
        return new StorageBinSearchRequest(
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
