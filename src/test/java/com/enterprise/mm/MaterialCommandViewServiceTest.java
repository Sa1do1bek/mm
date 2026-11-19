package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.InventoryAccountingSettings;
import com.enterprise.mm.entity.MaterialMarketPrice;
import com.enterprise.mm.entity.StockValuationHistory;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.payload.MaterialCommandViewDtos.DemandSupplyFilter;
import com.enterprise.mm.payload.MaterialCommandViewDtos.MetricStatus;
import com.enterprise.mm.payload.MaterialCommandViewDtos.RelationshipType;
import com.enterprise.mm.payload.MaterialCommandViewDtos.StockHealthResponse;
import com.enterprise.mm.payload.MaterialCommandViewDtos.SupplierFilter;
import com.enterprise.mm.payload.MaterialCommandViewDtos.SupplierRelationshipKind;
import com.enterprise.mm.payload.MaterialCommandViewDtos.SupplierScopeStatus;
import com.enterprise.mm.payload.MaterialCommandViewDtos.QualityCompatibilityFilter;
import com.enterprise.mm.payload.MaterialCommandViewDtos.QualityCompatibilityKind;
import com.enterprise.mm.payload.MaterialCommandViewDtos.ValuationFilter;
import com.enterprise.mm.payload.MaterialCommandViewDtos.ValuationResponse;
import com.enterprise.mm.repository.IlliquidStockPolicyRepository;
import com.enterprise.mm.repository.InventoryAccountingSettingsRepository;
import com.enterprise.mm.repository.MaterialDemandSupplyQueryRepository;
import com.enterprise.mm.repository.MaterialMarketPriceRepository;
import com.enterprise.mm.repository.NonMovingStockCaseLineRepository;
import com.enterprise.mm.repository.NonMovingStockRepository;
import com.enterprise.mm.repository.StockValuationHistoryRepository;
import com.enterprise.mm.repository.MaterialSupplierRelationshipQueryRepository;
import com.enterprise.mm.repository.MaterialSupplierRelationshipQueryRepository.SupplierEvidenceRecord;
import com.enterprise.mm.repository.MaterialQualityCompatibilityQueryRepository;
import com.enterprise.mm.repository.MaterialQualityCompatibilityQueryRepository.QualityEvidenceRecord;
import com.enterprise.mm.repository.SupplierAccreditationDecisionRepository;
import com.enterprise.mm.repository.MaterialRelationshipQueryRepository;
import com.enterprise.mm.service.*;
import com.enterprise.mm.service.MaterialCommandViewScopeGuard.MaterialScope;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MaterialCommandViewServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-13T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void noReadableDemandSourcePerformsNoRepositoryOrScopeLookup() {
        MaterialDemandSupplyQueryRepository repository = mock(MaterialDemandSupplyQueryRepository.class);
        MaterialDemandSupplyQueryService service = new MaterialDemandSupplyQueryService(repository);
        Material material = new Material();
        UUID materialId = UUID.randomUUID();
        material.setId(materialId);
        MaterialScope scope = new MaterialScope("tenant", UUID.randomUUID(), materialId, material);

        assertThat(service.page(scope, DemandSupplyFilter.empty(), Set.of(), PageRequest.of(0, 25))).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void valuationSeparatesAccountingHistoryFromMarketEvidence() {
        StockValuationHistoryRepository historyRepository = mock(StockValuationHistoryRepository.class);
        MaterialMarketPriceRepository marketPriceRepository = mock(MaterialMarketPriceRepository.class);
        InventoryAccountingSettingsRepository settingsRepository = mock(InventoryAccountingSettingsRepository.class);
        MaterialScope scope = scope();
        when(settingsRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(scope.tenantId(), scope.companyId()))
            .thenReturn(Optional.of(accountingSettings("UZS")));
        StockValuationHistory history = valuationHistory(scope, "100.0000", "2026-07-12T10:00:00");
        when(historyRepository.findMaterial360History(
            org.mockito.ArgumentMatchers.eq(scope.tenantId()),
            org.mockito.ArgumentMatchers.eq(scope.companyId()),
            org.mockito.ArgumentMatchers.eq(scope.materialId()),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.any()))
            .thenReturn(new PageImpl<>(List.of(history), PageRequest.of(0, 25), 1));
        when(historyRepository.findMaterial360Latest(
            scope.tenantId(), scope.companyId(), scope.materialId(), null)).thenReturn(Optional.of(history));
        when(marketPriceRepository.findMaterial360Evidence(
            scope.tenantId(), scope.companyId(), scope.materialId(), null, LocalDate.of(2026, 7, 13), PageRequest.of(0, 5)))
            .thenReturn(List.of(marketPrice(scope, "12.0000", "USD", LocalDate.of(2026, 7, 10))));
        MaterialValuationQueryService service = new MaterialValuationQueryService(
            historyRepository, marketPriceRepository, settingsRepository, CLOCK);

        ValuationResponse response = service.valuation(scope, new ValuationFilter(null, null), PageRequest.of(0, 25));

        assertThat(response.snapshot().stockValue().value()).isEqualTo("100.0000");
        assertThat(response.snapshot().stockValue().currencyCode()).isEqualTo("UZS");
        assertThat(response.marketPrices()).singleElement().satisfies(price -> {
            assertThat(price.currencyCode()).isEqualTo("USD");
            assertThat(price.unitPrice()).isEqualTo("12.0000");
        });
    }

    @Test
    void missingAccountingSettingsMakesStockMoneyNoData() {
        StockValuationHistoryRepository historyRepository = mock(StockValuationHistoryRepository.class);
        MaterialMarketPriceRepository marketPriceRepository = mock(MaterialMarketPriceRepository.class);
        InventoryAccountingSettingsRepository settingsRepository = mock(InventoryAccountingSettingsRepository.class);
        MaterialScope scope = scope();
        when(settingsRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(scope.tenantId(), scope.companyId()))
            .thenReturn(Optional.empty());
        when(historyRepository.findMaterial360History(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.any()))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 25), 0));
        when(marketPriceRepository.findMaterial360Evidence(
            scope.tenantId(), scope.companyId(), scope.materialId(), null, LocalDate.of(2026, 7, 13), PageRequest.of(0, 5)))
            .thenReturn(List.of());
        MaterialValuationQueryService service = new MaterialValuationQueryService(
            historyRepository, marketPriceRepository, settingsRepository, CLOCK);

        ValuationResponse response = service.valuation(scope, new ValuationFilter(null, null), PageRequest.of(0, 25));

        assertThat(response.snapshot().stockValue().status()).isEqualTo(MetricStatus.NO_DATA);
        assertThat(response.history()).isNotNull();
    }

    @Test
    void stockHealthUsesExistingCaseAndDoesNotCalculateMovementAge() {
        NonMovingStockCaseLineRepository caseLineRepository = mock(NonMovingStockCaseLineRepository.class);
        NonMovingStockRepository nonMovingStockRepository = mock(NonMovingStockRepository.class);
        IlliquidStockPolicyRepository policyRepository = mock(IlliquidStockPolicyRepository.class);
        MaterialScope scope = scope();
        UUID caseId = UUID.randomUUID();
        NonMovingStockCaseLineRepository.Material360CaseProjection evidence =
            mock(NonMovingStockCaseLineRepository.Material360CaseProjection.class);
        when(evidence.getId()).thenReturn(UUID.randomUUID());
        when(evidence.getCaseId()).thenReturn(caseId);
        when(evidence.getCaseNo()).thenReturn("NMSC-1");
        when(evidence.getCaseName()).thenReturn("Non-moving case");
        when(evidence.getCaseStatus()).thenReturn("UNDER_REVIEW");
        when(evidence.getDetectedAt()).thenReturn(LocalDateTime.of(2026, 7, 12, 9, 0));
        when(caseLineRepository.findMaterial360OpenCaseEvidence(
            scope.tenantId(), scope.companyId(), scope.materialId(), null, PageRequest.of(0, 5)))
            .thenReturn(List.of(evidence));
        when(caseLineRepository.countMaterial360OpenCases(
            scope.tenantId(), scope.companyId(), scope.materialId(), null)).thenReturn(1L);
        when(nonMovingStockRepository.countMaterial360Active(
            scope.tenantId(), scope.companyId(), scope.materialId(), null)).thenReturn(0L);
        when(policyRepository.findActivePoliciesForResolution(
            scope.tenantId(), scope.companyId(), LocalDate.of(2026, 7, 13))).thenReturn(List.of());
        MaterialStockHealthQueryService service = new MaterialStockHealthQueryService(
            caseLineRepository, nonMovingStockRepository, policyRepository, new IlliquidStockPolicyResolver());

        StockHealthResponse response = service.stockHealthSnapshot(scope, LocalDate.of(2026, 7, 13)).response();

        assertThat(response.nonMovingCaseOpen()).isTrue();
        assertThat(response.caseEvidence()).singleElement().satisfies(row -> {
            assertThat(row.type()).isEqualTo(RelationshipType.NON_MOVING_STOCK_CASE);
            assertThat(row.sourceId()).isEqualTo(caseId);
        });
    }

    @Test
    void purchaseHistoryDoesNotInferSupplierAccreditation() {
        MaterialSupplierRelationshipQueryRepository evidenceRepository = mock(MaterialSupplierRelationshipQueryRepository.class);
        SupplierAccreditationService accreditationService = mock(SupplierAccreditationService.class);
        MaterialScope scope = scope();
        UUID supplierId = UUID.randomUUID();
        SupplierEvidenceRecord evidence = new SupplierEvidenceRecord(
            UUID.randomUUID(), supplierId, "SUP-1", "Supplier", "PURCHASE_ORDER", UUID.randomUUID(),
            new BigDecimal("9.0000"), "UZS", LocalDate.of(2026, 7, 12), "purchase-orders");
        when(evidenceRepository.page(
            org.mockito.ArgumentMatchers.eq(scope.tenantId()), org.mockito.ArgumentMatchers.eq(scope.companyId()),
            org.mockito.ArgumentMatchers.eq(scope.materialId()), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any())).thenReturn(new PageImpl<>(List.of(evidence), PageRequest.of(0, 25), 1));
        MaterialSupplierQueryService service = new MaterialSupplierQueryService(
            evidenceRepository, accreditationService, CLOCK);

        var row = service.page(scope, new SupplierFilter(null, null, null), PageRequest.of(0, 25)).getContent().getFirst();

        assertThat(row.relationshipKind()).isEqualTo(SupplierRelationshipKind.TRANSACTIONAL_HISTORY);
        assertThat(row.scopeStatus()).isEqualTo(SupplierScopeStatus.UNKNOWN);
        assertThat(row.accreditationDecision()).isNull();
    }

    @Test
    void qualityMapsOnlyExplicitStoredEvidence() {
        MaterialQualityCompatibilityQueryRepository repository = mock(MaterialQualityCompatibilityQueryRepository.class);
        MaterialScope scope = scope();
        QualityEvidenceRecord evidence = new QualityEvidenceRecord(
            UUID.randomUUID(), QualityCompatibilityKind.EQUIPMENT_COMPATIBILITY, "ACTIVE", "Pump P-1",
            "REQUIRED", LocalDate.of(2026, 1, 1), null, "material-equipment-compatibilities", UUID.randomUUID());
        when(repository.page(
            org.mockito.ArgumentMatchers.eq(scope.tenantId()), org.mockito.ArgumentMatchers.eq(scope.companyId()),
            org.mockito.ArgumentMatchers.eq(scope.materialId()), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any())).thenReturn(new PageImpl<>(List.of(evidence), PageRequest.of(0, 25), 1));
        MaterialQualityQueryService service = new MaterialQualityQueryService(repository);

        var page = service.page(scope, new QualityCompatibilityFilter(null, null), PageRequest.of(0, 25));

        assertThat(page).singleElement().satisfies(row -> {
            assertThat(row.kind()).isEqualTo(QualityCompatibilityKind.EQUIPMENT_COMPATIBILITY);
            assertThat(row.displayName()).isEqualTo("Pump P-1");
        });
    }

    @Test
    void deniedRelationshipSourcesPerformNoRepositoryLookup() {
        MaterialRelationshipQueryRepository repository = mock(MaterialRelationshipQueryRepository.class);
        MaterialRelationshipQueryService service = new MaterialRelationshipQueryService(repository);
        MaterialScope scope = scope();

        assertThat(service.page(scope, com.enterprise.mm.payload.MaterialCommandViewDtos.RelationshipFilter.empty(),
            Set.of(), PageRequest.of(0, 25))).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void summaryTreatsUnconfiguredSafetyStockAsNoData() {
        MaterialCommandViewScopeGuard scopeGuard = mock(MaterialCommandViewScopeGuard.class);
        MaterialCommandViewAccessPolicy accessPolicy = mock(MaterialCommandViewAccessPolicy.class);
        MaterialInventoryQueryService inventory = mock(MaterialInventoryQueryService.class);
        MaterialDemandSupplyQueryService demand = mock(MaterialDemandSupplyQueryService.class);
        MaterialValuationQueryService valuation = mock(MaterialValuationQueryService.class);
        MaterialSupplierQueryService suppliers = mock(MaterialSupplierQueryService.class);
        MaterialQualityQueryService quality = mock(MaterialQualityQueryService.class);
        MaterialStockHealthQueryService stockHealth = mock(MaterialStockHealthQueryService.class);
        MaterialRelationshipQueryService relationships = mock(MaterialRelationshipQueryService.class);
        MaterialAttributeService attributes = mock(MaterialAttributeService.class);
        FixedAssetService fixedAssetService = mock(FixedAssetService.class);
        MaterialScope scope = scope();
        scope.material().setSafetyStock(BigDecimal.ZERO);
        when(scopeGuard.requireGoods(scope.materialId(), null)).thenReturn(scope);
        when(attributes.getByMaterialId(scope.materialId())).thenReturn(List.of());
        java.util.EnumMap<com.enterprise.mm.payload.MaterialCommandViewDtos.TabKey,
            com.enterprise.mm.payload.MaterialCommandViewDtos.Visibility> tabs =
            new java.util.EnumMap<>(com.enterprise.mm.payload.MaterialCommandViewDtos.TabKey.class);
        for (var tab : com.enterprise.mm.payload.MaterialCommandViewDtos.TabKey.values()) {
            tabs.put(tab, com.enterprise.mm.payload.MaterialCommandViewDtos.Visibility.DENIED);
        }
        tabs.put(com.enterprise.mm.payload.MaterialCommandViewDtos.TabKey.OVERVIEW,
            com.enterprise.mm.payload.MaterialCommandViewDtos.Visibility.READ);
        tabs.put(com.enterprise.mm.payload.MaterialCommandViewDtos.TabKey.AVAILABILITY,
            com.enterprise.mm.payload.MaterialCommandViewDtos.Visibility.READ);
        tabs.put(com.enterprise.mm.payload.MaterialCommandViewDtos.TabKey.MOVEMENTS,
            com.enterprise.mm.payload.MaterialCommandViewDtos.Visibility.READ);
        java.util.EnumMap<com.enterprise.mm.payload.MaterialCommandViewDtos.SectionKey,
            com.enterprise.mm.payload.MaterialCommandViewDtos.Visibility> sections =
            new java.util.EnumMap<>(com.enterprise.mm.payload.MaterialCommandViewDtos.SectionKey.class);
        for (var section : com.enterprise.mm.payload.MaterialCommandViewDtos.SectionKey.values()) {
            sections.put(section, com.enterprise.mm.payload.MaterialCommandViewDtos.Visibility.DENIED);
        }
        sections.put(com.enterprise.mm.payload.MaterialCommandViewDtos.SectionKey.INVENTORY,
            com.enterprise.mm.payload.MaterialCommandViewDtos.Visibility.READ);
        when(accessPolicy.tabVisibilities()).thenReturn(Map.copyOf(tabs));
        when(accessPolicy.sectionVisibilities()).thenReturn(Map.copyOf(sections));
        when(scopeGuard.resolveWarehouse(null)).thenReturn(null);
        when(inventory.aggregate(scope)).thenReturn(new MaterialInventoryQueryService.InventorySnapshot(
            BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN,
            "UZS", 1, LocalDateTime.of(2026, 7, 13, 9, 0)));
        when(inventory.availability(org.mockito.ArgumentMatchers.eq(scope), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 5), 0));
        when(inventory.movements(org.mockito.ArgumentMatchers.eq(scope), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 5), 0));
        MaterialCommandViewService service = new MaterialCommandViewService(
            scopeGuard, accessPolicy, inventory, demand, valuation, suppliers, quality, stockHealth,
            relationships, attributes, CLOCK, new MaterialCommandViewObservation(new SimpleMeterRegistry()), fixedAssetService);

        scope.material().setMaterialSubtype(com.enterprise.mm.enums.MaterialSubtype.EQUIPMENT);
        var response = service.summary(scope.materialId());
        assertThat(response.material().materialType()).isEqualTo(com.enterprise.mm.enums.MaterialType.TMB);
        assertThat(response.material().materialSubtype()).isEqualTo(com.enterprise.mm.enums.MaterialSubtype.EQUIPMENT);

        assertThat(response.commitments().safetyStockGap().status()).isEqualTo(MetricStatus.NO_DATA);
        verifyNoInteractions(suppliers);
    }

    private MaterialScope scope() {
        Material material = new Material();
        UUID materialId = UUID.randomUUID();
        material.setId(materialId);
        material.setValuationMethod(ValuationMethod.ACTUAL_COST);
        material.setMinStock(new BigDecimal("1.0000"));
        material.setSafetyStock(new BigDecimal("2.0000"));
        material.setReorderPoint(new BigDecimal("3.0000"));
        material.setMaxStock(new BigDecimal("10.0000"));
        return new MaterialScope("tenant", UUID.randomUUID(), materialId, material);
    }

    private InventoryAccountingSettings accountingSettings(String currencyCode) {
        InventoryAccountingSettings settings = new InventoryAccountingSettings();
        settings.setBaseCurrencyCode(currencyCode);
        return settings;
    }

    private StockValuationHistory valuationHistory(MaterialScope scope, String totalValue, String calculatedAt) {
        StockValuationHistory history = new StockValuationHistory();
        history.setId(UUID.randomUUID());
        history.setTenantId(scope.tenantId());
        history.setCompanyId(scope.companyId());
        history.setMaterialId(scope.materialId());
        history.setWarehouseId(UUID.randomUUID());
        history.setWarehouseName("WH-1");
        history.setQuantity(new BigDecimal("10.0000"));
        history.setUnitCost(new BigDecimal("10.0000"));
        history.setTotalValue(new BigDecimal(totalValue));
        history.setValuationMethod(ValuationMethod.ACTUAL_COST);
        history.setValuationBasis("STOCK_COST_JOB");
        history.setSourceType("STOCK_COST_JOB");
        history.setCalculatedAt(LocalDateTime.parse(calculatedAt));
        return history;
    }

    private MaterialMarketPrice marketPrice(
        MaterialScope scope,
        String price,
        String currencyCode,
        LocalDate effectiveDate
    ) {
        MaterialMarketPrice evidence = new MaterialMarketPrice();
        evidence.setId(UUID.randomUUID());
        evidence.setTenantId(scope.tenantId());
        evidence.setCompanyId(scope.companyId());
        evidence.setMaterialId(scope.materialId());
        evidence.setMarketUnitPrice(new BigDecimal(price));
        evidence.setCurrencyCode(currencyCode);
        evidence.setEffectiveDate(effectiveDate);
        evidence.setSourceType("MANUAL");
        evidence.setSourceReference("REF-1");
        evidence.setStatus("APPROVED");
        return evidence;
    }
}
