package com.enterprise.mm.payload;

import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.MaterialSubtype;
import com.enterprise.mm.enums.ValuationMethod;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MaterialCommandViewDtos {
    private MaterialCommandViewDtos() {
    }

    public enum TabKey {
        OVERVIEW,
        AVAILABILITY,
        DEMAND_SUPPLY,
        MOVEMENTS,
        VALUATION,
        SUPPLIERS,
        QUALITY_COMPATIBILITY,
        STOCK_HEALTH,
        RELATIONSHIPS,
        FIXED_ASSETS
    }

    public enum SectionKey {
        INVENTORY,
        PURCHASE_REQUESTS,
        PURCHASE_ORDERS,
        RESERVATIONS,
        VALUATION,
        MARKET_PRICE,
        SUPPLIERS,
        QUALITY,
        STOCK_HEALTH,
        DOCUMENTS,
        AUDIT,
        INTEGRATIONS,
        FIXED_ASSETS
    }

    public enum Visibility { READ, DENIED, UNAVAILABLE }
    public enum MetricStatus { AVAILABLE, NO_DATA, DENIED, STALE }
    public enum RiskKey { LIFECYCLE, SAFETY_REORDER, QUALITY_QUARANTINE, SUPPLY_DELAY, NON_MOVING, STALE_PRICE_VALUATION, INTEGRATION }
    public enum RiskSeverity { CRITICAL, HIGH, MEDIUM, LOW, INFO }
    public enum DemandSupplyKind { PURCHASE_REQUEST_DEMAND, RESERVATION_DEMAND, PURCHASE_ORDER_SUPPLY }
    public enum SupplierRelationshipKind { TRANSACTIONAL_HISTORY, APPROVED_SCOPE }
    public enum SupplierScopeStatus { APPROVED, UNKNOWN, DENIED }
    public enum QualityCompatibilityKind { QUALITY_ISSUE, ANALOGUE, EQUIPMENT_COMPATIBILITY, CONSUMPTION_NORM, REPLACEMENT }
    public enum RelationshipType {
        PURCHASE_REQUEST,
        PURCHASE_ORDER,
        RESERVATION,
        STOCK_MOVEMENT,
        NON_MOVING_STOCK_CASE,
        AUDIT_EVENT,
        INTEGRATION_EVENT
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

        public static DecimalMetric stale(BigDecimal value) {
            return new DecimalMetric(decimal(value), MetricStatus.STALE);
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

        public static MoneyMetric stale(BigDecimal value, String currency) {
            return new MoneyMetric(decimal(value), currency, MetricStatus.STALE);
        }
    }

    public enum AllowedModuleKey {
        MATERIALS("materials"),
        STOCK_BALANCES("stock-balances"),
        STOCK_MOVEMENTS("stock-movements"),
        MATERIAL_RESERVATIONS("material-reservations"),
        PURCHASE_REQUESTS("purchase-requests"),
        PURCHASE_ORDERS("purchase-orders"),
        MATERIAL_MARKET_PRICES("material-market-prices"),
        SUPPLIERS("suppliers"),
        MATERIAL_QUALITY_ISSUES("material-quality-issues"),
        MATERIAL_ANALOGS("material-analogs"),
        MATERIAL_EQUIPMENT_COMPATIBILITIES("material-equipment-compatibilities"),
        MATERIAL_CONSUMPTION_NORMS("material-consumption-norms"),
        NON_MOVING_STOCK_CASES("non-moving-stock-cases"),
        VALUATION("valuation"),
        AUDIT("audit"),
        INTEGRATION_EVENTS("integration-events"),
        REPORTS("reports");

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

    public record LinkTarget(String moduleKey, UUID recordId, Map<String, String> params) {
        public LinkTarget {
            AllowedModuleKey.require(moduleKey);
            params = params == null ? Map.of() : Map.copyOf(params);
        }

        public static LinkTarget of(String moduleKey, UUID recordId, Map<String, String> params) {
            return new LinkTarget(moduleKey, recordId, params);
        }
    }

    public record MaterialHeader(
        UUID id,
        String code,
        String name,
        String status,
        UUID materialGroupId,
        String materialGroupName,
        UUID unitOfMeasureId,
        String unitOfMeasureCode,
        String unitOfMeasureShortName,
        String unitOfMeasureName,
        String mxikCode,
        String mxikName,
        ValuationMethod valuationMethod,
        UUID replacementMaterialId,
        String replacementMaterialCode,
        String lifecycleReason,
        LocalDateTime lifecycleChangedAt,
        List<MaterialAttributeResponse> specifications,
        MaterialType materialType,
        MaterialSubtype materialSubtype
    ) {
        public MaterialHeader(
            UUID id, String code, String name, String status,
            UUID materialGroupId, String materialGroupName, UUID unitOfMeasureId,
            String unitOfMeasureCode, String unitOfMeasureShortName, String unitOfMeasureName,
            String mxikCode, String mxikName, ValuationMethod valuationMethod,
            UUID replacementMaterialId, String replacementMaterialCode,
            String lifecycleReason, LocalDateTime lifecycleChangedAt,
            List<MaterialAttributeResponse> specifications
        ) {
            this(id, code, name, status, materialGroupId, materialGroupName, unitOfMeasureId,
                unitOfMeasureCode, unitOfMeasureShortName, unitOfMeasureName, mxikCode, mxikName,
                valuationMethod, replacementMaterialId, replacementMaterialCode, lifecycleReason,
                lifecycleChangedAt, specifications, null, null);
        }
        public MaterialHeader(
            UUID id, String code, String name, String status,
            UUID materialGroupId, String materialGroupName, UUID unitOfMeasureId,
            String unitOfMeasureCode, String mxikCode,String mxikName, ValuationMethod valuationMethod,
            UUID replacementMaterialId, String replacementMaterialCode,
            String lifecycleReason, LocalDateTime lifecycleChangedAt,
            List<MaterialAttributeResponse> specifications
        ) {
            this(id, code, name, status, materialGroupId, materialGroupName, unitOfMeasureId,
                unitOfMeasureCode, null, null, mxikCode, mxikName, valuationMethod, replacementMaterialId,
                replacementMaterialCode, lifecycleReason, lifecycleChangedAt, specifications);
        }

        public MaterialHeader {
            specifications = specifications == null ? List.of() : List.copyOf(specifications);
        }
    }

    public record ScopeInfo(String tenantId, UUID companyId, UUID warehouseId) {
    }

    public record InventoryMetrics(
        DecimalMetric onHand,
        DecimalMetric available,
        DecimalMetric reserved,
        DecimalMetric quarantine,
        MoneyMetric totalValue,
        long warehouseCount
    ) {
    }

    public record CommitmentMetrics(DecimalMetric openDemand, DecimalMetric openSupply, DecimalMetric safetyStockGap) {
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

    public record OverviewPreviews(
        List<AvailabilityRow> availability,
        List<DemandSupplyRow> demandSupply,
        List<MovementRow> movements,
        List<SupplierRelationshipRow> suppliers,
        List<QualityCompatibilityRow> qualityCompatibility,
        List<RelationshipRow> relationships
    ) {
    }

    public record MaterialCommandViewResponse(
        MaterialHeader material,
        ScopeInfo scope,
        Instant asOf,
        Map<TabKey, Visibility> permissions,
        Map<SectionKey, Visibility> sectionPermissions,
        InventoryMetrics inventory,
        CommitmentMetrics commitments,
        List<Risk> risks,
        Map<TabKey, Long> tabCounts,
        OverviewPreviews overview,
        List<FixedAssetResponse> fixedAssets
    ) {
    }

    public record AvailabilityFilter(UUID warehouseId, UUID storageLocationId, UUID stockBatchId, boolean positiveOnly) {
    }

    public record MovementFilter(
        LocalDate dateFrom,
        LocalDate dateTo,
        UUID warehouseId,
        MovementType movementType,
        UUID stockBatchId
    ) {
    }

    public record DemandSupplyFilter(LocalDate dateFrom, LocalDate dateTo, UUID warehouseId, String status) {
        public static DemandSupplyFilter empty() {
            return new DemandSupplyFilter(null, null, null, null);
        }
    }

    public record ValuationFilter(LocalDate dateFrom, LocalDate dateTo) {
    }

    public record SupplierFilter(String sourceType, LocalDate dateFrom, LocalDate dateTo) {
    }

    public record QualityCompatibilityFilter(QualityCompatibilityKind kind, String status) {
    }

    public record RelationshipFilter(RelationshipType type, String status, LocalDate dateFrom, LocalDate dateTo) {
        public static RelationshipFilter empty() {
            return new RelationshipFilter(null, null, null, null);
        }
    }

    public record AvailabilityRow(
        UUID id,
        UUID warehouseId,
        String warehouseName,
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
        LinkTarget link
    ) {
    }

    public record MovementRow(
        UUID id,
        LocalDateTime postedAt,
        LocalDate movementDate,
        MovementType movementType,
        String status,
        String quantity,
        String unitOfMeasureCode,
        String unitOfMeasureShortName,
        UUID warehouseId,
        String warehouseName,
        UUID stockBatchId,
        String batchNumber,
        String referenceType,
        UUID referenceId,
        LinkTarget link
    ) {
    }

    public record DemandSupplyRow(
        UUID id,
        DemandSupplyKind kind,
        String name,
        String documentCode,
        String documentStatus,
        String openQuantity,
        String unitOfMeasureCode,
        String unitOfMeasureShortName,
        LocalDate relevantDate,
        UUID warehouseId,
        String warehouseName,
        LinkTarget link
    ) {
    }

    public record ValuationSnapshot(
        ValuationMethod valuationMethod,
        MoneyMetric stockValue,
        LocalDateTime calculatedAt,
        String valuationBasis
    ) {
    }

    public record ValuationHistoryRow(
        UUID id,
        UUID warehouseId,
        String warehouseName,
        String quantity,
        String unitCost,
        String totalValue,
        String currencyCode,
        ValuationMethod valuationMethod,
        String valuationBasis,
        LocalDateTime calculatedAt,
        LinkTarget link
    ) {
    }

    public record MarketPriceEvidence(
        UUID id,
        String unitPrice,
        String currencyCode,
        String sourceType,
        String sourceReference,
        LocalDate effectiveDate,
        MetricStatus status,
        LinkTarget link
    ) {
    }

    public record ValuationResponse(
        ValuationSnapshot snapshot,
        PageResponse<ValuationHistoryRow> history,
        List<MarketPriceEvidence> marketPrices
    ) {
    }

    public record SupplierRelationshipRow(
        UUID id,
        UUID supplierId,
        String supplierCode,
        String supplierName,
        SupplierRelationshipKind relationshipKind,
        String sourceType,
        UUID sourceId,
        String lastPrice,
        String currencyCode,
        LocalDate evidenceDate,
        SupplierScopeStatus scopeStatus,
        String accreditationDecision,
        LinkTarget link
    ) {
    }

    public record QualityCompatibilityRow(
        UUID id,
        QualityCompatibilityKind kind,
        String status,
        String displayName,
        String detail,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        LinkTarget link
    ) {
    }

    public record StockHealthResponse(
        DecimalMetric minStock,
        DecimalMetric safetyStock,
        DecimalMetric reorderPoint,
        DecimalMetric maxStock,
        boolean nonMovingCaseOpen,
        List<RelationshipRow> caseEvidence,
        List<LinkTarget> reportLinks
    ) {
    }

    public record RelationshipRow(
        UUID id,
        RelationshipType type,
        String businessCode,
        String displayName,
        String status,
        LocalDateTime occurredAt,
        String sourceType,
        UUID sourceId,
        LinkTarget link
    ) {
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
