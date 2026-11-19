package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record StockBalanceSearchRequest(
    String search,
    List<String> status,
    UUID warehouseId,
    UUID stockBatchId,
    UUID materialGroupId,
    List<UUID> materialIds,
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
    UUID materialId,
    UUID storageLocationId,
    UUID storageBinId,
    java.math.BigDecimal quantity,
    java.math.BigDecimal availableQuantity,
    java.math.BigDecimal reservedQuantity,
    java.math.BigDecimal quarantineQuantity,
    java.math.BigDecimal totalValue,
    @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime balanceAt,
    List<com.enterprise.mm.enums.StockExpiryStatus> expiryStatus,
    Boolean expiryWarning,
    StockBalanceViewOptions options
) implements SearchCriteria {
    public StockBalanceSearchRequest withOptions(StockBalanceViewOptions value) {
        return new StockBalanceSearchRequest(search, status, warehouseId, stockBatchId, materialGroupId, materialIds, dateFrom, dateTo, includeDeleted, id, code, name, description, deleted, createdBy, updatedBy, version, materialId, storageLocationId, storageBinId, quantity, availableQuantity, reservedQuantity, quarantineQuantity, totalValue, balanceAt, expiryStatus, expiryWarning, value);
    }

    public StockBalanceSearchRequest withWarehouseId(UUID value) {
        return new StockBalanceSearchRequest(search, status, value, stockBatchId, materialGroupId, materialIds, dateFrom, dateTo, includeDeleted, id, code, name, description, deleted, createdBy, updatedBy, version, materialId, storageLocationId, storageBinId, quantity, availableQuantity, reservedQuantity, quarantineQuantity, totalValue, balanceAt, expiryStatus, expiryWarning, options);
    }

    public static StockBalanceSearchRequest forWarehouse(UUID warehouseId, UUID storageLocationId,
        List<com.enterprise.mm.enums.StockExpiryStatus> expiryStatus, Boolean expiryWarning, StockBalanceViewOptions options) {
        return new StockBalanceSearchRequest(null, null, warehouseId, null, null, null, null, null, false, null, null, null, null, null, null, null, null, null, storageLocationId, null, null, null, null, null, null, null, expiryStatus, expiryWarning, options);
    }

    public static StockBalanceSearchRequest empty() {
        return new StockBalanceSearchRequest(
            null,
            null,
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
            null,
            null,
            null
        );
    }
}
