package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.IlliquidStockPolicy;
import com.enterprise.mm.entity.InventoryAccountingSettings;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialMarketPrice;
import com.enterprise.mm.entity.StockValuationHistory;
import com.enterprise.mm.payload.MaterialCommandViewDtos.AllowedModuleKey;
import com.enterprise.mm.payload.MaterialCommandViewDtos.DecimalMetric;
import com.enterprise.mm.payload.MaterialCommandViewDtos.LinkTarget;
import com.enterprise.mm.payload.MaterialCommandViewDtos.MarketPriceEvidence;
import com.enterprise.mm.payload.MaterialCommandViewDtos.MetricStatus;
import com.enterprise.mm.payload.MaterialCommandViewDtos.MoneyMetric;
import com.enterprise.mm.payload.MaterialCommandViewDtos.QualityCompatibilityFilter;
import com.enterprise.mm.payload.MaterialCommandViewDtos.RelationshipFilter;
import com.enterprise.mm.payload.MaterialCommandViewDtos.RelationshipRow;
import com.enterprise.mm.payload.MaterialCommandViewDtos.RelationshipType;
import com.enterprise.mm.payload.MaterialCommandViewDtos.RiskKey;
import com.enterprise.mm.payload.MaterialCommandViewDtos.SectionKey;
import com.enterprise.mm.payload.MaterialCommandViewDtos.StockHealthResponse;
import com.enterprise.mm.payload.MaterialCommandViewDtos.SupplierFilter;
import com.enterprise.mm.payload.MaterialCommandViewDtos.TabKey;
import com.enterprise.mm.payload.MaterialCommandViewDtos.ValuationFilter;
import com.enterprise.mm.payload.MaterialCommandViewDtos.ValuationHistoryRow;
import com.enterprise.mm.payload.MaterialCommandViewDtos.ValuationResponse;
import com.enterprise.mm.payload.MaterialCommandViewDtos.ValuationSnapshot;
import com.enterprise.mm.payload.MaterialCommandViewDtos.Visibility;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.SupplierEligibilityDtos.EffectiveSupplierEligibility;
import com.enterprise.mm.repository.IlliquidStockPolicyRepository;
import com.enterprise.mm.repository.InventoryAccountingSettingsRepository;
import com.enterprise.mm.repository.MaterialDemandSupplyQueryRepository;
import com.enterprise.mm.repository.MaterialMarketPriceRepository;
import com.enterprise.mm.repository.NonMovingStockCaseLineRepository;
import com.enterprise.mm.repository.NonMovingStockRepository;
import com.enterprise.mm.repository.StockValuationHistoryRepository;
import com.enterprise.mm.repository.MaterialSupplierRelationshipQueryRepository;
import com.enterprise.mm.repository.MaterialSupplierRelationshipQueryRepository.SupplierEvidenceRecord;
import com.enterprise.mm.repository.SupplierAccreditationDecisionRepository;
import com.enterprise.mm.service.IlliquidStockPolicyResolver;
import com.enterprise.mm.service.MaterialAttributeService;
import com.enterprise.mm.service.MaterialCommandViewAccessPolicy;
import com.enterprise.mm.service.MaterialCommandViewObservation;
import com.enterprise.mm.service.MaterialCommandViewScopeGuard;
import com.enterprise.mm.service.MaterialCommandViewScopeGuard.MaterialScope;
import com.enterprise.mm.service.MaterialCommandViewService;
import com.enterprise.mm.service.MaterialDemandSupplyQueryService;
import com.enterprise.mm.service.FixedAssetService;
import com.enterprise.mm.service.MaterialInventoryQueryService;
import com.enterprise.mm.service.MaterialQualityQueryService;
import com.enterprise.mm.service.MaterialRelationshipQueryService;
import com.enterprise.mm.service.MaterialStockHealthQueryService;
import com.enterprise.mm.service.MaterialSupplierQueryService;
import com.enterprise.mm.service.SupplierAccreditationService;
import com.enterprise.mm.service.MaterialValuationQueryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class MaterialCommandViewRequirementsCorrectionTest {
    private static final Instant AS_OF = Instant.parse("2026-07-13T23:59:59Z");
    private static final LocalDate AS_OF_DATE = LocalDate.of(2026, 7, 13);
    private static final Clock CLOCK = Clock.fixed(AS_OF, ZoneOffset.UTC);

    @Test
    void currentValuationSnapshotIgnoresHistoryDateFiltersAndMarketPermission() {
        StockValuationHistoryRepository historyRepository = mock(StockValuationHistoryRepository.class);
        MaterialMarketPriceRepository marketRepository = mock(MaterialMarketPriceRepository.class);
        InventoryAccountingSettingsRepository settingsRepository = mock(InventoryAccountingSettingsRepository.class);
        MaterialScope scope = scope();
        StockValuationHistory latest = valuation(scope, "200.0000", "2026-07-12T10:00:00");
        StockValuationHistory filtered = valuation(scope, "100.0000", "2025-01-15T10:00:00");
        when(historyRepository.findMaterial360Latest(
            scope.tenantId(), scope.companyId(), scope.materialId(), scope.warehouseId()))
            .thenReturn(Optional.of(latest));
        when(historyRepository.findMaterial360History(
            eq(scope.tenantId()), eq(scope.companyId()), eq(scope.materialId()), eq(scope.warehouseId()),
            eq(LocalDate.of(2025, 1, 1).atStartOfDay()), eq(LocalDate.of(2025, 2, 1).atStartOfDay()), any()))
            .thenReturn(new PageImpl<>(List.of(filtered), PageRequest.of(0, 25), 1));
        when(settingsRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(scope.tenantId(), scope.companyId()))
            .thenReturn(Optional.of(settings("UZS")));
        MaterialValuationQueryService service = new MaterialValuationQueryService(
            historyRepository, marketRepository, settingsRepository, CLOCK);

        ValuationResponse response = service.valuation(
            scope,
            new ValuationFilter(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 31)),
            Set.of(SectionKey.VALUATION),
            PageRequest.of(0, 25),
            AS_OF_DATE);

        assertThat(response.snapshot().stockValue().value()).isEqualTo("200.0000");
        assertThat(response.history().content()).extracting(ValuationHistoryRow::totalValue)
            .containsExactly("100.0000");
        assertThat(response.marketPrices()).isEmpty();
        verifyNoInteractions(marketRepository);
    }

    @Test
    void marketOnlyValuationSkipsAccountingSources() {
        StockValuationHistoryRepository historyRepository = mock(StockValuationHistoryRepository.class);
        MaterialMarketPriceRepository marketRepository = mock(MaterialMarketPriceRepository.class);
        InventoryAccountingSettingsRepository settingsRepository = mock(InventoryAccountingSettingsRepository.class);
        MaterialScope scope = scope();
        when(marketRepository.findMaterial360Evidence(
            scope.tenantId(), scope.companyId(), scope.materialId(), scope.warehouseId(), AS_OF_DATE,
            PageRequest.of(0, 5)))
            .thenReturn(List.of(market(scope, LocalDate.of(2026, 7, 12))));
        MaterialValuationQueryService service = new MaterialValuationQueryService(
            historyRepository, marketRepository, settingsRepository, CLOCK);

        ValuationResponse response = service.valuation(
            scope, new ValuationFilter(null, null), Set.of(SectionKey.MARKET_PRICE),
            PageRequest.of(0, 25), AS_OF_DATE);

        assertThat(response.snapshot().stockValue().status()).isEqualTo(MetricStatus.DENIED);
        assertThat(response.history().content()).isEmpty();
        assertThat(response.marketPrices()).hasSize(1);
        verifyNoInteractions(historyRepository, settingsRepository);
    }

    @Test
    void governedPolicyResolverUsesTheSameCreatedAtTieBreakEverywhere() {
        IlliquidStockPolicy older = policy("OLDER", LocalDateTime.of(2026, 7, 1, 10, 0));
        IlliquidStockPolicy newer = policy("NEWER", LocalDateTime.of(2026, 7, 2, 10, 0));

        Optional<IlliquidStockPolicy> resolved = new IlliquidStockPolicyResolver().resolve(
            List.of(older, newer), null, null, "CHEMICAL", "GOODS");

        assertThat(resolved).get().extracting(IlliquidStockPolicy::getCode).isEqualTo("NEWER");
    }

    @Test
    void supplierAccreditationLookupReceivesExactMaterialGroupBoundary() {
        MaterialSupplierRelationshipQueryRepository evidenceRepository =
            mock(MaterialSupplierRelationshipQueryRepository.class);
        SupplierAccreditationService accreditationService = mock(SupplierAccreditationService.class);
        MaterialScope scope = scope();
        UUID groupId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        scope.material().setMaterialGroupId(groupId);
        SupplierEvidenceRecord evidence = new SupplierEvidenceRecord(
            UUID.randomUUID(), supplierId, "SUP-1", "Supplier", "PURCHASE_ORDER", UUID.randomUUID(),
            BigDecimal.TEN, "UZS", AS_OF_DATE, "purchase-orders");
        when(evidenceRepository.page(eq(scope.tenantId()), eq(scope.companyId()), eq(scope.materialId()),
            any(), any())).thenReturn(new PageImpl<>(List.of(evidence), PageRequest.of(0, 25), 1));
        when(accreditationService.effectiveEligibility(
            supplierId, List.of(groupId), AS_OF_DATE, "material 360")).thenReturn(
                new EffectiveSupplierEligibility(
                    supplierId, AS_OF_DATE, "NOT_ELIGIBLE", false, List.of(), List.of(), null));
        MaterialSupplierQueryService service = new MaterialSupplierQueryService(
            evidenceRepository, accreditationService, CLOCK);

        service.page(scope, new SupplierFilter(null, null, null), PageRequest.of(0, 25), AS_OF_DATE);

        verify(accreditationService).effectiveEligibility(
            supplierId, List.of(groupId), AS_OF_DATE, "material 360");
    }

    @Test
    @SuppressWarnings("unchecked")
    void overdueSupplyLookupStopsAtTheFirstMatchingRow() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(ResultSetExtractor.class)))
            .thenReturn(true);
        MaterialDemandSupplyQueryRepository repository = new MaterialDemandSupplyQueryRepository(jdbc);

        assertThat(repository.overdueSupplyExists(
            "tenant", UUID.randomUUID(), UUID.randomUUID(), null, AS_OF_DATE)).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(MapSqlParameterSource.class), any(ResultSetExtractor.class));
        assertThat(sql.getValue()).contains("select 1", "limit 1").doesNotContain("count(*)");
    }

    @Test
    void stockHealthCountUsesDedicatedSourceCountsInsteadOfFiveRowEvidence() {
        NonMovingStockCaseLineRepository caseRepository = mock(NonMovingStockCaseLineRepository.class);
        NonMovingStockRepository detectionRepository = mock(NonMovingStockRepository.class);
        IlliquidStockPolicyRepository policyRepository = mock(IlliquidStockPolicyRepository.class);
        MaterialScope scope = scope();
        when(caseRepository.findMaterial360OpenCaseEvidence(
            scope.tenantId(), scope.companyId(), scope.materialId(), scope.warehouseId(), PageRequest.of(0, 5)))
            .thenReturn(List.of());
        when(caseRepository.countMaterial360OpenCases(
            scope.tenantId(), scope.companyId(), scope.materialId(), scope.warehouseId())).thenReturn(6L);
        when(detectionRepository.countMaterial360Active(
            scope.tenantId(), scope.companyId(), scope.materialId(), scope.warehouseId())).thenReturn(0L);
        when(policyRepository.findActivePoliciesForResolution(
            scope.tenantId(), scope.companyId(), AS_OF_DATE)).thenReturn(List.of());
        MaterialStockHealthQueryService service = new MaterialStockHealthQueryService(
            caseRepository, detectionRepository, policyRepository, new IlliquidStockPolicyResolver());

        var snapshot = service.stockHealthSnapshot(scope, AS_OF_DATE);

        assertThat(snapshot.count()).isEqualTo(6);
        assertThat(snapshot.response().caseEvidence()).isEmpty();
    }

    @Test
    void activeDetectionProducesAStockHealthCountWhenNoCaseExists() {
        NonMovingStockCaseLineRepository caseRepository = mock(NonMovingStockCaseLineRepository.class);
        NonMovingStockRepository detectionRepository = mock(NonMovingStockRepository.class);
        IlliquidStockPolicyRepository policyRepository = mock(IlliquidStockPolicyRepository.class);
        MaterialScope scope = scope();
        when(caseRepository.findMaterial360OpenCaseEvidence(
            scope.tenantId(), scope.companyId(), scope.materialId(), scope.warehouseId(), PageRequest.of(0, 5)))
            .thenReturn(List.of());
        when(caseRepository.countMaterial360OpenCases(
            scope.tenantId(), scope.companyId(), scope.materialId(), scope.warehouseId())).thenReturn(0L);
        when(detectionRepository.countMaterial360Active(
            scope.tenantId(), scope.companyId(), scope.materialId(), scope.warehouseId())).thenReturn(1L);
        when(policyRepository.findActivePoliciesForResolution(
            scope.tenantId(), scope.companyId(), AS_OF_DATE)).thenReturn(List.of());
        MaterialStockHealthQueryService service = new MaterialStockHealthQueryService(
            caseRepository, detectionRepository, policyRepository, new IlliquidStockPolicyResolver());

        var snapshot = service.stockHealthSnapshot(scope, AS_OF_DATE);

        assertThat(snapshot.count()).isEqualTo(1);
        assertThat(snapshot.response().nonMovingCaseOpen()).isTrue();
    }

    @Test
    void integrationRiskUsesDedicatedLookupInsteadOfRelationshipPreview() {
        SummaryHarness harness = new SummaryHarness();
        harness.allow(TabKey.RELATIONSHIPS, SectionKey.INTEGRATIONS);
        RelationshipRow failed = integration("FAILED", AS_OF.minusSeconds(3600));
        when(harness.relationships.latestIntegrationRisk(harness.scope)).thenReturn(Optional.of(failed));

        var response = harness.summary();

        assertThat(response.risks()).extracting(risk -> risk.key()).contains(RiskKey.INTEGRATION);
        verify(harness.relationships).latestIntegrationRisk(harness.scope);
    }

    @Test
    void readableOpenQualityIssueProducesQualityRiskWithoutQuarantine() {
        SummaryHarness harness = new SummaryHarness();
        harness.allow(TabKey.AVAILABILITY, SectionKey.INVENTORY);
        harness.allow(TabKey.QUALITY_COMPATIBILITY, SectionKey.QUALITY);
        when(harness.quality.openIssueExists(harness.scope)).thenReturn(true);

        var response = harness.summary();

        assertThat(response.risks()).extracting(risk -> risk.key())
            .containsExactly(RiskKey.QUALITY_QUARANTINE);
        verify(harness.quality).openIssueExists(harness.scope);
    }

    @Test
    void qualityOnlyRiskLinksToReadableQualityIssuesInsteadOfDeniedInventory() {
        SummaryHarness harness = new SummaryHarness();
        harness.allow(TabKey.QUALITY_COMPATIBILITY, SectionKey.QUALITY);
        when(harness.quality.openIssueExists(harness.scope)).thenReturn(true);

        var response = harness.summary();

        assertThat(response.risks()).singleElement().satisfies(risk -> {
            assertThat(risk.key()).isEqualTo(RiskKey.QUALITY_QUARANTINE);
            assertThat(risk.sourceType()).isEqualTo("MATERIAL_QUALITY_ISSUE");
            assertThat(risk.link().moduleKey()).isEqualTo(AllowedModuleKey.MATERIAL_QUALITY_ISSUES.key());
        });
    }

    @Test
    void staleRiskUsesOnlyTheLatestReadablePrice() {
        SummaryHarness harness = new SummaryHarness();
        harness.allow(TabKey.VALUATION, SectionKey.MARKET_PRICE);
        ValuationResponse valuation = new ValuationResponse(
            new ValuationSnapshot(null, MoneyMetric.denied(), null, null),
            new PageResponse<>(List.of(), 0, 25, 0, 0),
            List.of(
                marketEvidence(LocalDate.of(2026, 7, 12), MetricStatus.AVAILABLE),
                marketEvidence(LocalDate.of(2025, 1, 1), MetricStatus.STALE)));
        when(harness.valuation.valuation(
            eq(harness.scope), any(), eq(Set.of(SectionKey.MARKET_PRICE)), any(), eq(AS_OF_DATE)))
            .thenReturn(valuation);

        var response = harness.summary();

        assertThat(response.risks()).extracting(risk -> risk.key())
            .doesNotContain(RiskKey.STALE_PRICE_VALUATION);
    }

    @Test
    void summaryPassesItsSingleAsOfDateAndUsesDedicatedStockCount() {
        SummaryHarness harness = new SummaryHarness();
        harness.allow(TabKey.VALUATION, SectionKey.VALUATION);
        harness.allow(TabKey.SUPPLIERS, SectionKey.SUPPLIERS);
        harness.allow(TabKey.STOCK_HEALTH, SectionKey.STOCK_HEALTH);
        when(harness.valuation.valuation(
            eq(harness.scope), any(), eq(Set.of(SectionKey.VALUATION)), any(), eq(AS_OF_DATE)))
            .thenReturn(noDataValuation());
        when(harness.stockHealth.stockHealthSnapshot(harness.scope, AS_OF_DATE))
            .thenReturn(new MaterialStockHealthQueryService.StockHealthSnapshot(stockHealth(false), 7));

        var response = harness.summary();

        assertThat(response.tabCounts()).containsEntry(TabKey.STOCK_HEALTH, 7L);
        verify(harness.valuation).valuation(eq(harness.scope), any(), any(), any(), eq(AS_OF_DATE));
        verify(harness.suppliers).page(eq(harness.scope), any(SupplierFilter.class), any(), eq(AS_OF_DATE));
        verify(harness.stockHealth).stockHealthSnapshot(harness.scope, AS_OF_DATE);
    }

    private static final class SummaryHarness {
        final MaterialCommandViewScopeGuard scopeGuard = mock(MaterialCommandViewScopeGuard.class);
        final MaterialCommandViewAccessPolicy accessPolicy = mock(MaterialCommandViewAccessPolicy.class);
        final MaterialInventoryQueryService inventory = mock(MaterialInventoryQueryService.class);
        final MaterialDemandSupplyQueryService demand = mock(MaterialDemandSupplyQueryService.class);
        final MaterialValuationQueryService valuation = mock(MaterialValuationQueryService.class);
        final MaterialSupplierQueryService suppliers = mock(MaterialSupplierQueryService.class);
        final MaterialQualityQueryService quality = mock(MaterialQualityQueryService.class);
        final MaterialStockHealthQueryService stockHealth = mock(MaterialStockHealthQueryService.class);
        final MaterialRelationshipQueryService relationships = mock(MaterialRelationshipQueryService.class);
        final MaterialAttributeService attributes = mock(MaterialAttributeService.class);
        final FixedAssetService fixedAssets = mock(FixedAssetService.class);
        final MaterialScope scope = scope();
        final EnumMap<TabKey, Visibility> tabs = deniedTabs();
        final EnumMap<SectionKey, Visibility> sections = deniedSections();
        final MaterialCommandViewService service = new MaterialCommandViewService(
            scopeGuard, accessPolicy, inventory, demand, valuation, suppliers, quality, stockHealth,
            relationships, attributes, CLOCK, new MaterialCommandViewObservation(new SimpleMeterRegistry()), fixedAssets);

        SummaryHarness() {
            tabs.put(TabKey.OVERVIEW, Visibility.READ);
            when(scopeGuard.requireGoods(scope.materialId(), null)).thenReturn(scope);
            when(attributes.getByMaterialId(scope.materialId())).thenReturn(List.of());
            when(inventory.aggregate(scope)).thenReturn(new MaterialInventoryQueryService.InventorySnapshot(
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN,
                "UZS", 1, LocalDateTime.of(2026, 7, 13, 20, 0)));
            when(inventory.availability(eq(scope), any(), any())).thenReturn(Page.empty(PageRequest.of(0, 5)));
            when(inventory.movements(eq(scope), any(), any())).thenReturn(Page.empty(PageRequest.of(0, 5)));
            when(quality.page(eq(scope), any(QualityCompatibilityFilter.class), any()))
                .thenReturn(Page.empty(PageRequest.of(0, 5)));
            when(relationships.page(eq(scope), any(RelationshipFilter.class), any(), any()))
                .thenReturn(Page.empty(PageRequest.of(0, 5)));
            when(suppliers.page(eq(scope), any(SupplierFilter.class), any(), eq(AS_OF_DATE)))
                .thenReturn(Page.empty(PageRequest.of(0, 5)));
        }

        void allow(TabKey tab, SectionKey section) {
            tabs.put(tab, Visibility.READ);
            sections.put(section, Visibility.READ);
        }

        com.enterprise.mm.payload.MaterialCommandViewDtos.MaterialCommandViewResponse summary() {
            when(accessPolicy.tabVisibilities()).thenReturn(Map.copyOf(tabs));
            when(accessPolicy.sectionVisibilities()).thenReturn(Map.copyOf(sections));
            return service.summary(scope.materialId());
        }
    }

    private static EnumMap<TabKey, Visibility> deniedTabs() {
        EnumMap<TabKey, Visibility> result = new EnumMap<>(TabKey.class);
        for (TabKey key : TabKey.values()) result.put(key, Visibility.DENIED);
        return result;
    }

    private static EnumMap<SectionKey, Visibility> deniedSections() {
        EnumMap<SectionKey, Visibility> result = new EnumMap<>(SectionKey.class);
        for (SectionKey key : SectionKey.values()) result.put(key, Visibility.DENIED);
        return result;
    }

    private static MaterialScope scope() {
        Material material = new Material();
        material.setId(UUID.randomUUID());
        material.setStatus("ACTIVE");
        material.setValuationMethod(com.enterprise.mm.enums.ValuationMethod.ACTUAL_COST);
        return new MaterialScope("tenant", UUID.randomUUID(), material.getId(), material, null);
    }

    private static StockValuationHistory valuation(MaterialScope scope, String value, String calculatedAt) {
        StockValuationHistory history = new StockValuationHistory();
        history.setId(UUID.randomUUID());
        history.setTenantId(scope.tenantId());
        history.setCompanyId(scope.companyId());
        history.setMaterialId(scope.materialId());
        history.setQuantity(BigDecimal.TEN);
        history.setUnitCost(BigDecimal.TEN);
        history.setTotalValue(new BigDecimal(value));
        history.setValuationMethod(com.enterprise.mm.enums.ValuationMethod.ACTUAL_COST);
        history.setValuationBasis("JOB");
        history.setCalculatedAt(LocalDateTime.parse(calculatedAt));
        return history;
    }

    private static InventoryAccountingSettings settings(String currency) {
        InventoryAccountingSettings settings = new InventoryAccountingSettings();
        settings.setBaseCurrencyCode(currency);
        return settings;
    }

    private static MaterialMarketPrice market(MaterialScope scope, LocalDate effectiveDate) {
        MaterialMarketPrice price = new MaterialMarketPrice();
        price.setId(UUID.randomUUID());
        price.setTenantId(scope.tenantId());
        price.setCompanyId(scope.companyId());
        price.setMaterialId(scope.materialId());
        price.setMarketUnitPrice(BigDecimal.TEN);
        price.setCurrencyCode("USD");
        price.setSourceType("MANUAL");
        price.setEffectiveDate(effectiveDate);
        return price;
    }

    private static IlliquidStockPolicy policy(String code, LocalDateTime createdAt) {
        IlliquidStockPolicy policy = new IlliquidStockPolicy();
        policy.setId(UUID.randomUUID());
        policy.setCode(code);
        policy.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        policy.setCreatedAt(createdAt);
        policy.setMaterialCategory("CHEMICAL");
        policy.setMaterialType("GOODS");
        return policy;
    }

    private static RelationshipRow integration(String status, Instant occurredAt) {
        UUID id = UUID.randomUUID();
        return new RelationshipRow(id, RelationshipType.INTEGRATION_EVENT, "INT-1", "Sync", status,
            LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC), "MATERIAL_SYNC", id,
            LinkTarget.of(AllowedModuleKey.INTEGRATION_EVENTS.key(), id, Map.of()));
    }

    private static MarketPriceEvidence marketEvidence(LocalDate date, MetricStatus status) {
        UUID id = UUID.randomUUID();
        return new MarketPriceEvidence(id, "10.0000", "USD", "MANUAL", null, date, status,
            LinkTarget.of(AllowedModuleKey.MATERIAL_MARKET_PRICES.key(), id, Map.of()));
    }

    private static ValuationResponse noDataValuation() {
        return new ValuationResponse(
            new ValuationSnapshot(null, MoneyMetric.noData(), null, null),
            new PageResponse<>(List.of(), 0, 25, 0, 0), List.of());
    }

    private static StockHealthResponse stockHealth(boolean active) {
        return new StockHealthResponse(DecimalMetric.noData(), DecimalMetric.noData(), DecimalMetric.noData(),
            DecimalMetric.noData(), active, List.of(), List.of());
    }
}
