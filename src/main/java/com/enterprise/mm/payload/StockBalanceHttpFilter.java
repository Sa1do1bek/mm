package com.enterprise.mm.payload;

/** Mutable HTTP binding model preserves optional primitive defaults and shares every list/export filter. */
@lombok.Getter @lombok.Setter
public class StockBalanceHttpFilter extends StockBalanceViewOptions {
    private String search;
    private java.util.List<String> status;
    private java.util.UUID warehouseId;
    private java.util.UUID stockBatchId;
    private java.util.UUID materialGroupId;
    private java.util.List<java.util.UUID> materialIds;
    @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
    private java.time.LocalDate dateFrom;
    @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
    private java.time.LocalDate dateTo;
    private boolean includeDeleted;
    private java.util.UUID id;
    private String code;
    private String name;
    private String description;
    private Boolean deleted;
    private String createdBy;
    private String updatedBy;
    private Long version;
    private java.util.UUID materialId;
    private java.util.UUID storageLocationId;
    private java.util.UUID storageBinId;
    private java.math.BigDecimal quantity;
    private java.math.BigDecimal availableQuantity;
    private java.math.BigDecimal reservedQuantity;
    private java.math.BigDecimal quarantineQuantity;
    private java.math.BigDecimal totalValue;
    @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
    private java.time.LocalDateTime balanceAt;
    private java.util.List<com.enterprise.mm.enums.StockExpiryStatus> expiryStatus;
    private Boolean expiryWarning;
    public StockBalanceViewOptions toOptions() {
        var options=new StockBalanceViewOptions();
        org.springframework.beans.BeanUtils.copyProperties(this,options);
        return options;
    }
    public StockBalanceSearchRequest toRequest() {
        return new StockBalanceSearchRequest(search, status, warehouseId, stockBatchId, materialGroupId, materialIds, dateFrom, dateTo, includeDeleted, id, code, name, description, deleted, createdBy, updatedBy, version, materialId, storageLocationId, storageBinId, quantity, availableQuantity, reservedQuantity, quarantineQuantity, totalValue, balanceAt, expiryStatus, expiryWarning, toOptions());
    }
}
