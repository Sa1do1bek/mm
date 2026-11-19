package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.enums.StorageZoneType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record StorageLocationSearchRequest(
    String search,
    List<String> status,
    UUID warehouseId,
    StorageZoneType storageZoneType,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    boolean includeDeleted,
    UUID id,
    String code,
    String name,
    String description,
    Boolean deleted,
    String createdBy,
    String updatedBy,
    Long version,
    String zoneCode,
    String binCode,
    String barcode,
    String qrCode,
    String rfidTag,
    String temperatureZone,
    String hazardClassAllowed,
    java.math.BigDecimal capacityQuantity,
    java.math.BigDecimal maxWeight,
    Boolean allowMixedMaterials
) implements SearchCriteria {
    public static StorageLocationSearchRequest empty() {
        return new StorageLocationSearchRequest(
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
