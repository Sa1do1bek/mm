package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.TabKey;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.Visibility;
import com.enterprise.mm.repository.FixedAssetRepository;
import com.enterprise.mm.repository.InventoryAccountingSettingsRepository;
import com.enterprise.mm.repository.MaterialReservationRepository;
import com.enterprise.mm.repository.NonMovingStockCaseLineRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class WarehouseCommandViewFixedAssetsServiceTest {
    private static final String TENANT = "warehouse-command-assets";
    private static final UUID COMPANY_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID WAREHOUSE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Mock WarehouseRepository warehouseRepository;
    @Mock StockBalanceRepository stockBalanceRepository;
    @Mock StockMovementRepository stockMovementRepository;
    @Mock StorageLocationRepository storageLocationRepository;
    @Mock MaterialReservationRepository materialReservationRepository;
    @Mock NonMovingStockCaseLineRepository nonMovingStockCaseLineRepository;
    @Mock InventoryAccountingSettingsRepository accountingSettingsRepository;
    @Mock FixedAssetRepository fixedAssetRepository;
    @Mock RoleDataScopeService roleDataScopeService;

    @Mock private StockBalanceService balanceReads;
    private WarehouseCommandViewService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY_ID);

        service = new WarehouseCommandViewService(
            warehouseRepository,
            stockBalanceRepository,
            stockMovementRepository,
            storageLocationRepository,
            materialReservationRepository,
            nonMovingStockCaseLineRepository,
            accountingSettingsRepository,
            fixedAssetRepository,
            roleDataScopeService,
            Clock.fixed(Instant.parse("2026-10-20T10:00:00Z"), ZoneOffset.UTC),
            balanceReads
        );

        Warehouse warehouse = new Warehouse();
        warehouse.setId(WAREHOUSE_ID);
        warehouse.setTenantId(TENANT);
        warehouse.setCompanyId(COMPANY_ID);
        warehouse.setCode("WH-01");
        warehouse.setName("Central warehouse");
        warehouse.setStatus("ACTIVE");
        when(warehouseRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, WAREHOUSE_ID))
            .thenReturn(Optional.of(warehouse));
        when(roleDataScopeService.canAccessWarehouse(WAREHOUSE_ID)).thenReturn(true);
        when(accountingSettingsRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TENANT, COMPANY_ID))
            .thenReturn(Optional.empty());
        when(stockBalanceRepository.aggregateWarehouse360(TENANT, COMPANY_ID, WAREHOUSE_ID)).thenReturn(null);
        when(storageLocationRepository.countWarehouse360Locations(TENANT, COMPANY_ID, WAREHOUSE_ID)).thenReturn(0L);
        when(balanceReads.search(any(), any())).thenReturn(Page.empty());
        when(storageLocationRepository.findWarehouse360Locations(anyString(), any(), any(), any(Pageable.class)))
            .thenReturn(Page.empty());
        when(stockMovementRepository.findWarehouse360Movements(anyString(), any(), any(), any(), any(), any(), any(Pageable.class)))
            .thenReturn(Page.empty());
        when(materialReservationRepository.findWarehouse360Reservations(anyString(), any(), any(), any(), any(Pageable.class)))
            .thenReturn(Page.empty());
        when(nonMovingStockCaseLineRepository.findWarehouse360OpenCases(anyString(), any(), any(), any(Pageable.class)))
            .thenReturn(Page.empty());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void summaryIncludesWarehouseCustodyAssetCountPreviewAndLink() {
        FixedAsset asset = new FixedAsset();
        asset.setId(UUID.fromString("30000000-0000-0000-0000-000000000001"));
        asset.setMaterialId(UUID.fromString("40000000-0000-0000-0000-000000000001"));
        asset.setInventoryNumber("INV-001");
        asset.setSerialNumber("SER-001");
        asset.setLifecycleStatus(FixedAssetLifecycleStatus.IN_WAREHOUSE);
        asset.setCustodyKind(FixedAssetCustodyKind.WAREHOUSE);
        asset.setOperatingLocation("Zone A");
        Material material = new Material();
        material.setName("Forklift");
        asset.setMaterial(material);

        when(fixedAssetRepository.findByTenantIdAndCompanyIdAndWarehouseIdAndCustodyKindAndIsDeletedFalse(
            eq(TENANT), eq(COMPANY_ID), eq(WAREHOUSE_ID), eq(FixedAssetCustodyKind.WAREHOUSE), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(asset), PageRequest.of(0, 5), 7));

        var result = service.summary(WAREHOUSE_ID);

        assertThat(result.permissions()).containsEntry(TabKey.FIXED_ASSETS, Visibility.READ);
        assertThat(result.tabCounts()).containsEntry(TabKey.FIXED_ASSETS, 7L);
        assertThat(result.overview().fixedAssets()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(asset.getId());
            assertThat(row.materialName()).isEqualTo("Forklift");
            assertThat(row.inventoryNumber()).isEqualTo("INV-001");
            assertThat(row.lifecycleStatus()).isEqualTo("IN_WAREHOUSE");
            assertThat(row.link().moduleKey()).isEqualTo("fixed-assets");
            assertThat(row.link().params()).containsEntry("assetId", asset.getId().toString());
        });
    }
}
