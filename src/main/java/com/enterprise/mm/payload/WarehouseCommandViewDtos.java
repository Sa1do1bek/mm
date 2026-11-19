package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WarehouseCommandViewDtos {
    private WarehouseCommandViewDtos() {
    }

    public enum TabKey {
        OVERVIEW,
        BALANCES,
        FIXED_ASSETS,
        LOCATIONS,
        MOVEMENTS,
        RESERVATIONS,
        NON_MOVING
    }

    public enum Visibility { READ, DENIED, UNAVAILABLE }

    public enum MetricStatus { AVAILABLE, NO_DATA, DENIED, STALE }

    public enum RiskKey {
        INACTIVE,
        HIGH_QUARANTINE,
        NON_MOVING,
        ZERO_STOCK
    }

    public enum RiskSeverity { CRITICAL, HIGH, MEDIUM, LOW, INFO }

    public enum AllowedModuleKey {
        WAREHOUSES("warehouses"),
        STOCK_BALANCES("stock-balances"),
        STOCK_MOVEMENTS("stock-movements"),
        MATERIAL_RESERVATIONS("material-reservations"),
        STORAGE_LOCATIONS("storage-locations"),
        MATERIALS("materials"),
        FIXED_ASSETS("fixed-assets"),
        NON_MOVING_STOCK_CASES("non-moving-stock-cases"),
        NON_MOVING_STOCK_DASHBOARD("non-moving-stock-dashboard"),
        WAREHOUSE_EXECUTION("warehouse-execution");

        private final String key;

        AllowedModuleKey(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public static AllowedModuleKey require(String value) {
            return Arrays.stream(values())
                .filter(item -> item.key.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported module key: " + value));
        }
    }

    public record DecimalMetric(String value, MetricStatus status) {
        public static DecimalMetric available(BigDecimal value) {
            return new DecimalMetric(decimal(value), MetricStatus.AVAILABLE);
        }

        public static DecimalMetric noData() {
            return new DecimalMetric(null, MetricStatus.NO_DATA);
        }

        public static DecimalMetric denied() {
            return new DecimalMetric(null, MetricStatus.DENIED);
        }
    }

    public record MoneyMetric(String value, String currencyCode, MetricStatus status) {
        public static MoneyMetric available(BigDecimal value, String currency) {
            return new MoneyMetric(decimal(value), currency, MetricStatus.AVAILABLE);
        }

        public static MoneyMetric noData() {
            return new MoneyMetric(null, null, MetricStatus.NO_DATA);
        }

        public static MoneyMetric denied() {
            return new MoneyMetric(null, null, MetricStatus.DENIED);
        }
    }

    public record LinkTarget(String moduleKey, UUID recordId, Map<String, String> params) {
        public LinkTarget {
            AllowedModuleKey.require(moduleKey);
            params = params == null ? Map.of() : Map.copyOf(params);
        }

        public static LinkTarget of(String moduleKey, UUID recordId, Map<String, String> params) {
            return new LinkTarget(moduleKey, recordId, params);
        }
    }

    public record WarehouseHeader(
        UUID id,
        String code,
        String name,
        String status,
        String address,
        String description
    ) {
    }

    public record ScopeInfo(String tenantId, UUID companyId, UUID warehouseId) {
    }

    public record InventoryMetrics(
        DecimalMetric onHand,
        DecimalMetric available,
        DecimalMetric reserved,
        DecimalMetric quarantine,
        MoneyMetric totalValue,
        long materialCount,
        long locationCount,
        long positiveBalanceCount
    ) {
    }

    public record Risk(
        RiskKey key,
        RiskSeverity severity,
        String messageKey,
        String sourceType,
        UUID sourceId,
        LinkTarget link
    ) {
    }

    public record BalanceRow(
        UUID id,
        UUID materialId,
        String materialCode,
        String materialName,
        UUID storageLocationId,
        String storageLocationName,
        UUID stockBatchId,
        String batchNumber,
        String onHandQuantity,
        String availableQuantity,
        String reservedQuantity,
        String quarantineQuantity,
        String totalValue,
        String currencyCode,
        LocalDateTime balanceAt,
        LinkTarget link,
        LocalDate expirationDate,
        Long daysUntilExpiry,
        com.enterprise.mm.enums.StockExpiryStatus expiryStatus,
        boolean expiryWarning,
        LocalDate expiryAsOfDate,
        UUID warehouseId,
        String warehouseCode,
        String warehouseName,
        UUID materialGroupId,
        String materialGroupCode,
        String materialGroupName,
        UUID unitOfMeasureId,
        String unitCode,
        String unitLabel,
        UUID storageBinId,
        String storageBinName,
        String manufacturerBatchNumber,
        LocalDate manufacturedOn,
        LocalDate receivedDate
    ) {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "unitShortName", access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public String unitShortName() { return com.enterprise.mm.common.UnitOfMeasureLabels.shortLabel(unitLabel); }

        public static BalanceRow from(StockBalanceResponse r, String currency) {
            return new BalanceRow(r.getId(), r.getMaterialId(), r.getMaterialCode(), r.getMaterialName(),
                r.getStorageLocationId(), r.getStorageLocationName(), r.getStockBatchId(), r.getStockBatchNumber(),
                decimal(r.getQuantity()), decimal(r.getAvailableQuantity()), decimal(r.getReservedQuantity()), decimal(r.getQuarantineQuantity()),
                currency==null?null:decimal(r.getTotalValue()), currency, r.getBalanceAt(),
                LinkTarget.of(AllowedModuleKey.MATERIALS.key(), r.getMaterialId(), Map.of()),
                r.getExpirationDate(), r.getDaysUntilExpiry(), r.getExpiryStatus(), r.isExpiryWarning(), r.getExpiryAsOfDate(),
                r.getWarehouseId(), r.getWarehouseCode(), r.getWarehouseName(), r.getMaterialGroupId(), r.getMaterialGroupCode(),
                r.getMaterialGroupName(), r.getUnitOfMeasureId(), r.getUnitCode(), r.getUnitLabel(), r.getStorageBinId(),
                r.getStorageBinName(), r.getManufacturerBatchNumber(), r.getManufacturedOn(), r.getReceivedDate());
        }
        private static String decimal(BigDecimal value) { return value==null?null:value.toPlainString(); }
    }

    public record FixedAssetRow(
        UUID id,
        UUID materialId,
        String materialName,
        String inventoryNumber,
        String serialNumber,
        String lifecycleStatus,
        String custodyKind,
        String operatingLocation,
        LinkTarget link
    ) {
    }

    public record LocationRow(
        UUID id,
        String code,
        String name,
        String status,
        String zoneCode,
        String binCode,
        String storageZoneType,
        String temperatureZone,
        Boolean allowMixedMaterials,
        LinkTarget link
    ) {
    }

    public record MovementRow(
        UUID id,
        LocalDateTime postedAt,
        LocalDate movementDate,
        String movementType,
        String status,
        String quantity,
        String unitOfMeasureCode,
        String unitOfMeasureShortName,
        UUID materialId,
        String materialCode,
        String materialName,
        String batchNumber,
        String referenceType,
        UUID referenceId,
        LinkTarget link
    ) {
    }

    public record ReservationRow(
        UUID id,
        String code,
        String status,
        UUID materialId,
        String materialCode,
        String materialName,
        String quantity,
        String reservedQuantity,
        LocalDateTime expiresAt,
        String reservationType,
        String priority,
        LinkTarget link
    ) {
    }

    public record NonMovingRow(
        UUID id,
        UUID caseId,
        String caseNo,
        String caseName,
        String caseStatus,
        UUID materialId,
        String materialCode,
        String materialName,
        LocalDateTime detectedAt,
        LinkTarget link
    ) {
    }

    public record OverviewPreviews(
        List<BalanceRow> balances,
        List<FixedAssetRow> fixedAssets,
        List<LocationRow> locations,
        List<MovementRow> movements,
        List<ReservationRow> reservations,
        List<NonMovingRow> nonMoving
    ) {
    }

    public record WarehouseCommandViewResponse(
        WarehouseHeader warehouse,
        ScopeInfo scope,
        Instant asOf,
        Map<TabKey, Visibility> permissions,
        InventoryMetrics inventory,
        List<Risk> risks,
        Map<TabKey, Long> tabCounts,
        OverviewPreviews overview
    ) {
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
