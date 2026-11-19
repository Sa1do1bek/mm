package com.enterprise.mm.service;

import com.enterprise.mm.entity.Material;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.MaterialSubtype;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.payload.FixedAssetResponse;
import com.enterprise.mm.payload.MaterialCommandViewDtos.*;
import com.enterprise.mm.repository.MaterialDemandSupplyQueryRepository.DemandSupplyTotals;
import com.enterprise.mm.service.MaterialCommandViewScopeGuard.MaterialScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;
import org.springframework.context.i18n.LocaleContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MaterialCommandViewService {
    private static final PageRequest PREVIEW = PageRequest.of(0, 5);

    private final MaterialCommandViewScopeGuard scopeGuard;
    private final MaterialCommandViewAccessPolicy accessPolicy;
    private final MaterialInventoryQueryService inventoryService;
    private final MaterialDemandSupplyQueryService demandSupplyService;
    private final MaterialValuationQueryService valuationService;
    private final MaterialSupplierQueryService supplierService;
    private final MaterialQualityQueryService qualityService;
    private final MaterialStockHealthQueryService stockHealthService;
    private final MaterialRelationshipQueryService relationshipService;
    private final MaterialAttributeService materialAttributeService;
    private final Clock materialCommandViewClock;
    private final MaterialCommandViewObservation observation;
    private final FixedAssetService fixedAssetService;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MaterialCommandViewResponse summary(UUID materialId) {
        return summary(materialId, LocaleContextHolder.getLocale());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MaterialCommandViewResponse summary(UUID materialId, Locale locale) {
        return observation.observe(TabKey.OVERVIEW, materialId, () -> summaryInternal(materialId, locale));
    }

    private MaterialCommandViewResponse summaryInternal(UUID materialId, Locale locale) {
        accessPolicy.requireReadable(TabKey.OVERVIEW);
        Instant asOf = materialCommandViewClock.instant();
        LocalDate asOfDate = LocalDate.ofInstant(asOf, materialCommandViewClock.getZone());
        MaterialScope scope = scopeGuard.requireGoods(materialId, null);
        Map<TabKey, Visibility> tabs = accessPolicy.tabVisibilities();
        Map<SectionKey, Visibility> sections = accessPolicy.sectionVisibilities();
        UUID warehouseId = scope.warehouseId();

        MaterialInventoryQueryService.InventorySnapshot inventory = readable(sections, SectionKey.INVENTORY)
            ? inventoryService.aggregate(scope) : null;
        Page<AvailabilityRow> availability = readable(tabs, TabKey.AVAILABILITY)
            ? inventoryService.availability(scope, new AvailabilityFilter(warehouseId, null, null, false), PREVIEW)
            : Page.empty(PREVIEW);
        Page<MovementRow> movements = readable(tabs, TabKey.MOVEMENTS)
            ? inventoryService.movements(scope, new MovementFilter(null, null, warehouseId, null, null), PREVIEW)
            : Page.empty(PREVIEW);

        Set<SectionKey> demandSections = readableSections(sections,
            SectionKey.PURCHASE_REQUESTS, SectionKey.PURCHASE_ORDERS, SectionKey.RESERVATIONS);
        Page<DemandSupplyRow> demandRows = readable(tabs, TabKey.DEMAND_SUPPLY)
            ? demandSupplyService.page(scope, DemandSupplyFilter.empty(), demandSections, PREVIEW)
            : Page.empty(PREVIEW);
        DemandSupplyTotals demandTotals = demandSections.isEmpty() ? null : demandSupplyService.totals(scope, demandSections);

        Set<SectionKey> valuationSections = readableSections(
            sections, SectionKey.VALUATION, SectionKey.MARKET_PRICE);
        ValuationResponse valuation = readable(tabs, TabKey.VALUATION)
            ? valuationService.valuation(
                scope, new ValuationFilter(null, null), valuationSections, PREVIEW, asOfDate) : null;
        Page<SupplierRelationshipRow> suppliers = readable(tabs, TabKey.SUPPLIERS)
            ? supplierService.page(scope, new SupplierFilter(null, null, null), PREVIEW, asOfDate) : Page.empty(PREVIEW);
        Page<QualityCompatibilityRow> quality = readable(tabs, TabKey.QUALITY_COMPATIBILITY)
            ? qualityService.page(scope, new QualityCompatibilityFilter(null, null), PREVIEW) : Page.empty(PREVIEW);
        boolean openQualityIssue = readable(sections, SectionKey.QUALITY) && qualityService.openIssueExists(scope);
        MaterialStockHealthQueryService.StockHealthSnapshot stockHealth = readable(tabs, TabKey.STOCK_HEALTH)
            ? stockHealthService.stockHealthSnapshot(scope, asOfDate) : null;
        Set<SectionKey> relationshipSections = readableSections(sections,
            SectionKey.DOCUMENTS, SectionKey.AUDIT, SectionKey.INTEGRATIONS);
        Page<RelationshipRow> relationships = readable(tabs, TabKey.RELATIONSHIPS)
            ? relationshipService.page(scope, RelationshipFilter.empty(), relationshipSections, PREVIEW)
            : Page.empty(PREVIEW);
        Optional<RelationshipRow> integrationRisk = readable(sections, SectionKey.INTEGRATIONS)
            ? relationshipService.latestIntegrationRisk(scope) : Optional.empty();

        InventoryMetrics inventoryMetrics = inventoryMetrics(inventory, readable(sections, SectionKey.INVENTORY));
        CommitmentMetrics commitmentMetrics = commitmentMetrics(scope.material(), inventory, demandTotals, demandSections);
        List<Risk> risks = risks(
            scope, inventory, demandSections, stockHealth == null ? null : stockHealth.response(), valuation,
            openQualityIssue, integrationRisk, asOfDate);
        Page<FixedAssetResponse> fixedAssetsPage = readable(tabs, TabKey.FIXED_ASSETS)
            ? fixedAssetService.fixedAssetMaterials(scope.tenantId(), scope.companyId(), materialId, PREVIEW)
            : Page.empty(PREVIEW);
        Map<TabKey, Long> counts = counts(tabs, availability, demandRows, movements, valuation, suppliers, quality,
            stockHealth, relationships, fixedAssetsPage);
        OverviewPreviews previews = new OverviewPreviews(
            availability.getContent(), demandRows.getContent(), movements.getContent(), suppliers.getContent(),
            quality.getContent(), relationships.getContent());
        return new MaterialCommandViewResponse(header(scope.material(), locale),
            new ScopeInfo(scope.tenantId(), scope.companyId(), warehouseId), asOf, tabs, sections,
            inventoryMetrics, commitmentMetrics, risks, counts, previews, fixedAssetsPage.getContent());
    }

    public Page<AvailabilityRow> availability(UUID id, AvailabilityFilter filter, Pageable pageable) {
        return observation.observe(TabKey.AVAILABILITY, id, () -> {
            accessPolicy.requireReadable(TabKey.AVAILABILITY);
            AvailabilityFilter requested = filter == null
                ? new AvailabilityFilter(null, null, null, false) : filter;
            MaterialScope scope = scopeGuard.requireGoods(id, requested.warehouseId());
            return inventoryService.availability(scope, new AvailabilityFilter(
                scope.warehouseId(), requested.storageLocationId(), requested.stockBatchId(),
                requested.positiveOnly()), pageable);
        });
    }

    public Page<MovementRow> movements(UUID id, MovementFilter filter, Pageable pageable) {
        return observation.observe(TabKey.MOVEMENTS, id, () -> {
            accessPolicy.requireReadable(TabKey.MOVEMENTS);
            MovementFilter requested = filter == null
                ? new MovementFilter(null, null, null, null, null) : filter;
            MaterialScope scope = scopeGuard.requireGoods(id, requested.warehouseId());
            return inventoryService.movements(scope, new MovementFilter(
                requested.dateFrom(), requested.dateTo(), scope.warehouseId(), requested.movementType(),
                requested.stockBatchId()), pageable);
        });
    }

    public Page<DemandSupplyRow> demandSupply(UUID id, DemandSupplyFilter filter, Pageable pageable) {
        return observation.observe(TabKey.DEMAND_SUPPLY, id, () -> {
            accessPolicy.requireReadable(TabKey.DEMAND_SUPPLY);
            DemandSupplyFilter requested = filter == null ? DemandSupplyFilter.empty() : filter;
            MaterialScope scope = scopeGuard.requireGoods(id, requested.warehouseId());
            return demandSupplyService.page(scope, new DemandSupplyFilter(
                requested.dateFrom(), requested.dateTo(), scope.warehouseId(), requested.status()),
                readableSections(accessPolicy.sectionVisibilities(), SectionKey.PURCHASE_REQUESTS,
                    SectionKey.PURCHASE_ORDERS, SectionKey.RESERVATIONS), pageable);
        });
    }

    public ValuationResponse valuation(UUID id, ValuationFilter filter, Pageable pageable) {
        return observation.observe(TabKey.VALUATION, id, () -> {
            accessPolicy.requireReadable(TabKey.VALUATION);
            MaterialScope scope = scopeGuard.requireGoods(id, null);
            return valuationService.valuation(scope, filter,
                readableSections(accessPolicy.sectionVisibilities(), SectionKey.VALUATION, SectionKey.MARKET_PRICE),
                pageable, LocalDate.now(materialCommandViewClock));
        });
    }

    public Page<SupplierRelationshipRow> suppliers(UUID id, SupplierFilter filter, Pageable pageable) {
        return observation.observe(TabKey.SUPPLIERS, id, () -> {
            accessPolicy.requireReadable(TabKey.SUPPLIERS);
            MaterialScope scope = scopeGuard.requireGoods(id, null);
            return supplierService.page(scope, filter, pageable, LocalDate.now(materialCommandViewClock));
        });
    }

    public Page<QualityCompatibilityRow> qualityCompatibility(
        UUID id, QualityCompatibilityFilter filter, Pageable pageable
    ) {
        return observation.observe(TabKey.QUALITY_COMPATIBILITY, id, () -> {
            accessPolicy.requireReadable(TabKey.QUALITY_COMPATIBILITY);
            return qualityService.page(scopeGuard.requireGoods(id, null), filter, pageable);
        });
    }

    public StockHealthResponse stockHealth(UUID id) {
        return observation.observe(TabKey.STOCK_HEALTH, id, () -> {
            accessPolicy.requireReadable(TabKey.STOCK_HEALTH);
            return stockHealthService.stockHealthSnapshot(
                scopeGuard.requireGoods(id, null), LocalDate.now(materialCommandViewClock)).response();
        });
    }

    public Page<RelationshipRow> relationships(UUID id, RelationshipFilter filter, Pageable pageable) {
        return observation.observe(TabKey.RELATIONSHIPS, id, () -> {
            accessPolicy.requireReadable(TabKey.RELATIONSHIPS);
            return relationshipService.page(scopeGuard.requireGoods(id, null), filter,
                readableSections(accessPolicy.sectionVisibilities(), SectionKey.DOCUMENTS, SectionKey.AUDIT,
                    SectionKey.INTEGRATIONS), pageable);
        });
    }

    public Page<FixedAssetResponse> fixedAssets(UUID id, Pageable pageable) {
        return observation.observe(TabKey.FIXED_ASSETS, id, () -> {
            accessPolicy.requireReadable(TabKey.FIXED_ASSETS);
            MaterialScope scope = scopeGuard.requireGoods(id, null);
            return fixedAssetService.fixedAssetMaterials(scope.tenantId(), scope.companyId(), id, pageable);
        });
    }

    private InventoryMetrics inventoryMetrics(MaterialInventoryQueryService.InventorySnapshot value, boolean allowed) {
        if (!allowed) return new InventoryMetrics(DecimalMetric.denied(), DecimalMetric.denied(), DecimalMetric.denied(),
            DecimalMetric.denied(), MoneyMetric.denied(), 0);
        MoneyMetric total = value.currencyCode() == null ? MoneyMetric.noData()
            : MoneyMetric.available(value.totalValue(), value.currencyCode());
        return new InventoryMetrics(DecimalMetric.available(value.onHand()), DecimalMetric.available(value.available()),
            DecimalMetric.available(value.reserved()), DecimalMetric.available(value.quarantine()), total,
            value.warehouseCount());
    }

    private CommitmentMetrics commitmentMetrics(
        Material material, MaterialInventoryQueryService.InventorySnapshot inventory, DemandSupplyTotals totals,
        Set<SectionKey> demandSections
    ) {
        boolean demandReadable = demandSections.contains(SectionKey.PURCHASE_REQUESTS)
            || demandSections.contains(SectionKey.RESERVATIONS);
        boolean supplyReadable = demandSections.contains(SectionKey.PURCHASE_ORDERS);
        DecimalMetric demand = demandReadable ? DecimalMetric.available(totals.openDemand()) : DecimalMetric.denied();
        DecimalMetric supply = supplyReadable ? DecimalMetric.available(totals.openSupply()) : DecimalMetric.denied();
        DecimalMetric safety;
        if (inventory == null) safety = DecimalMetric.denied();
        else if (material.getSafetyStock() == null || material.getSafetyStock().signum() <= 0) safety = DecimalMetric.noData();
        else safety = DecimalMetric.available(material.getSafetyStock().subtract(inventory.available()).max(BigDecimal.ZERO));
        return new CommitmentMetrics(demand, supply, safety);
    }

    private List<Risk> risks(
        MaterialScope scope, MaterialInventoryQueryService.InventorySnapshot inventory, Set<SectionKey> demandSections,
        StockHealthResponse health, ValuationResponse valuation, boolean openQualityIssue,
        Optional<RelationshipRow> integrationRisk, LocalDate asOfDate
    ) {
        List<Risk> risks = new ArrayList<>();
        String status = scope.material().getStatus();
        if ("PROHIBITED".equals(status) || "OBSOLETE".equals(status)) {
            risks.add(new Risk(RiskKey.LIFECYCLE, "PROHIBITED".equals(status) ? RiskSeverity.CRITICAL : RiskSeverity.HIGH,
                "material360.risk.lifecycle", "MATERIAL", scope.materialId(),
                LinkTarget.of(AllowedModuleKey.MATERIALS.key(), scope.materialId(), Map.of())));
        }
        if (inventory != null && thresholdBreached(scope.material(), inventory.available()))
            risks.add(new Risk(RiskKey.SAFETY_REORDER, RiskSeverity.HIGH, "material360.risk.safety_reorder",
                "STOCK_BALANCE", null, LinkTarget.of(AllowedModuleKey.STOCK_BALANCES.key(), null,
                Map.of("materialId", scope.materialId().toString()))));
        boolean quarantinedStock = inventory != null && inventory.quarantine() != null
            && inventory.quarantine().signum() > 0;
        if (quarantinedStock || openQualityIssue) {
            AllowedModuleKey module = quarantinedStock
                ? AllowedModuleKey.STOCK_BALANCES : AllowedModuleKey.MATERIAL_QUALITY_ISSUES;
            String sourceType = quarantinedStock ? "STOCK_BALANCE" : "MATERIAL_QUALITY_ISSUE";
            risks.add(new Risk(RiskKey.QUALITY_QUARANTINE, RiskSeverity.HIGH,
                "material360.risk.quality_quarantine", sourceType, null,
                LinkTarget.of(module.key(), null, Map.of("materialId", scope.materialId().toString()))));
        }
        if (demandSections.contains(SectionKey.PURCHASE_ORDERS)
            && demandSupplyService.overdueSupplyExists(scope, demandSections, asOfDate))
            risks.add(new Risk(RiskKey.SUPPLY_DELAY, RiskSeverity.HIGH, "material360.risk.supply_delay",
                "PURCHASE_ORDER", null, LinkTarget.of(AllowedModuleKey.PURCHASE_ORDERS.key(), null,
                Map.of("materialId", scope.materialId().toString()))));
        if (health != null && health.nonMovingCaseOpen())
            risks.add(new Risk(RiskKey.NON_MOVING, RiskSeverity.MEDIUM, "material360.risk.non_moving",
                "NON_MOVING_STOCK_CASE", null, health.reportLinks().isEmpty() ? null : health.reportLinks().get(0)));
        if (valuation != null && (valuation.snapshot().stockValue().status() == MetricStatus.STALE
            || (!valuation.marketPrices().isEmpty()
                && valuation.marketPrices().get(0).status() == MetricStatus.STALE)))
            risks.add(new Risk(RiskKey.STALE_PRICE_VALUATION, RiskSeverity.LOW,
                "material360.risk.stale_price_valuation", "VALUATION", null,
                LinkTarget.of(AllowedModuleKey.VALUATION.key(), null, Map.of("materialId", scope.materialId().toString()))));
        integrationRisk.ifPresent(row -> risks.add(new Risk(RiskKey.INTEGRATION, RiskSeverity.MEDIUM,
                "material360.risk.integration", "INTEGRATION_EVENT", row.sourceId(), row.link())));
        return List.copyOf(risks);
    }

    private boolean thresholdBreached(Material material, BigDecimal available) {
        return available != null && ((material.getSafetyStock() != null && material.getSafetyStock().signum() > 0
            && available.compareTo(material.getSafetyStock()) < 0)
            || (material.getReorderPoint() != null && material.getReorderPoint().signum() > 0
            && available.compareTo(material.getReorderPoint()) <= 0));
    }

    private Map<TabKey, Long> counts(
        Map<TabKey, Visibility> tabs, Page<AvailabilityRow> availability, Page<DemandSupplyRow> demand,
        Page<MovementRow> movements, ValuationResponse valuation, Page<SupplierRelationshipRow> suppliers,
        Page<QualityCompatibilityRow> quality, MaterialStockHealthQueryService.StockHealthSnapshot health,
        Page<RelationshipRow> relationships, Page<FixedAssetResponse> fixedAssetsPage
    ) {
        Map<TabKey, Long> result = new EnumMap<>(TabKey.class);
        if (readable(tabs, TabKey.AVAILABILITY)) result.put(TabKey.AVAILABILITY, availability.getTotalElements());
        if (readable(tabs, TabKey.DEMAND_SUPPLY)) result.put(TabKey.DEMAND_SUPPLY, demand.getTotalElements());
        if (readable(tabs, TabKey.MOVEMENTS)) result.put(TabKey.MOVEMENTS, movements.getTotalElements());
        if (readable(tabs, TabKey.VALUATION)) result.put(TabKey.VALUATION,
            valuation.history().totalElements() + valuation.marketPrices().size());
        if (readable(tabs, TabKey.SUPPLIERS)) result.put(TabKey.SUPPLIERS, suppliers.getTotalElements());
        if (readable(tabs, TabKey.QUALITY_COMPATIBILITY)) result.put(TabKey.QUALITY_COMPATIBILITY, quality.getTotalElements());
        if (readable(tabs, TabKey.STOCK_HEALTH)) result.put(TabKey.STOCK_HEALTH, health.count());
        if (readable(tabs, TabKey.RELATIONSHIPS)) result.put(TabKey.RELATIONSHIPS, relationships.getTotalElements());
        if (readable(tabs, TabKey.FIXED_ASSETS)) result.put(TabKey.FIXED_ASSETS, fixedAssetsPage.getTotalElements());
        return Map.copyOf(result);
    }

    private MaterialHeader header(Material material, Locale locale) {
        UnitOfMeasure unit = material.getUnitOfMeasure();
        return new MaterialHeader(material.getId(), material.getCode(), material.getName(), material.getStatus(),
            material.getMaterialGroupId(), material.getMaterialGroup() == null ? null : material.getMaterialGroup().getName(),
            material.getUnitOfMeasureId(), unit == null ? null : unit.getCode(),
            unit == null ? null : localizedShortName(unit, locale),
            unit == null ? null : localizedLongName(unit, locale),
            material.getMxikCode(), material.getMxik() == null ? null : material.getMxik().getName(),
            material.getValuationMethod(), material.getReplacementMaterialId(),
            material.getReplacementMaterial() == null ? null : material.getReplacementMaterial().getCode(),
            material.getLifecycleReason(), material.getLifecycleChangedAt(),
            materialAttributeService.getByMaterialId(material.getId()),
            material.getMaterialType() == null ? MaterialType.TMB : material.getMaterialType(),
            material.getMaterialType() == null || material.getMaterialType() == MaterialType.TMB
                ? (material.getMaterialSubtype() == null ? MaterialSubtype.MATERIAL : material.getMaterialSubtype())
                : null);
    }

    private String localizedShortName(UnitOfMeasure unit, Locale locale) {
        return com.enterprise.mm.common.UnitOfMeasureLabels.localizedShort(unit, locale);
    }

    private String localizedLongName(UnitOfMeasure unit, Locale locale) {
        return switch (language(locale)) {
            case "uz" -> firstNonBlank(unit.getMeasurementNameLongUz(), unit.getMeasurementNameLong(), unit.getName());
            case "ru" -> firstNonBlank(unit.getMeasurementNameLongRu(), unit.getMeasurementNameLong(), unit.getName());
            default -> firstNonBlank(unit.getMeasurementNameLong(), unit.getName());
        };
    }

    private String language(Locale locale) {
        return locale == null ? "en" : locale.getLanguage().toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private Set<SectionKey> readableSections(Map<SectionKey, Visibility> map, SectionKey... keys) {
        return java.util.Arrays.stream(keys).filter(key -> readable(map, key))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private <K> boolean readable(Map<K, Visibility> map, K key) {
        return map.getOrDefault(key, Visibility.DENIED) == Visibility.READ;
    }
}
