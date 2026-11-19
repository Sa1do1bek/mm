package com.enterprise.mm.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.NonMovingStockCase;
import com.enterprise.mm.entity.NonMovingStockCaseLine;
import com.enterprise.mm.entity.StockBatch;
import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBatchRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NonMovingStockCaseMapperTest {
    @Mock MaterialRepository materialRepository;
    @Mock WarehouseRepository warehouseRepository;
    @Mock StorageLocationRepository storageLocationRepository;
    @Mock StockBatchRepository stockBatchRepository;
    @Mock UserRepository userRepository;

    @Test
    void addsHumanReferencesWithoutReplacingScopedIdentifiers() {
        UUID companyId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        NonMovingStockCase stockCase = base(new NonMovingStockCase(), companyId, "CASE-1", "Case 1");
        stockCase.setResponsibleUserId(ownerId);
        stockCase.setAccountingCurrencyCode("USD");
        stockCase.setValuationHealth("PARTIAL");
        stockCase.setValuationHealthMessage("Legacy provenance retained");
        NonMovingStockCaseLine line = base(new NonMovingStockCaseLine(), companyId, "LINE-1", "Line 1");
        line.setMaterialId(materialId);
        line.setWarehouseId(warehouseId);
        line.setStorageLocationId(locationId);
        line.setBatchId(batchId);
        line.setCurrencyCode("USD");
        line.setValuationHealth("PARTIAL");
        line.setValuationHealthMessage("Legacy provenance retained");

        Material material = base(new Material(), companyId, "MAT-10", "Bearing 6205");
        Warehouse warehouse = base(new Warehouse(), companyId, "WH-1", "Central warehouse");
        StorageLocation location = base(new StorageLocation(), companyId, "A-01-02", "Aisle A");
        StockBatch batch = base(new StockBatch(), companyId, "LOT-2026", "Lot 2026");
        User owner = base(new User(), companyId, "USR-1", "Account");
        owner.setFullName("Aziza Karimova");
        when(materialRepository.findByTenantIdAndIdAndIsDeletedFalse("tenant-a", materialId)).thenReturn(Optional.of(material));
        when(warehouseRepository.findByTenantIdAndIdAndIsDeletedFalse("tenant-a", warehouseId)).thenReturn(Optional.of(warehouse));
        when(storageLocationRepository.findByIdAndIsDeletedFalse(locationId)).thenReturn(Optional.of(location));
        when(stockBatchRepository.findByIdAndIsDeletedFalse(batchId)).thenReturn(Optional.of(batch));
        when(userRepository.findByIdAndIsDeletedFalse(ownerId)).thenReturn(Optional.of(owner));

        var result = mapper().toResponse(stockCase, List.of(line), List.of(), List.of());

        assertThat(result.getResponsibleDisplayName()).isEqualTo("Aziza Karimova");
        assertThat(result.getPrimaryMaterialName()).isEqualTo("Bearing 6205");
        assertThat(result.getPrimaryWarehouseName()).isEqualTo("Central warehouse");
        assertThat(result.getLineCount()).isEqualTo(1);
        assertThat(result.getAccountingCurrencyCode()).isEqualTo("USD");
        assertThat(result.getValuationHealth()).isEqualTo("PARTIAL");
        assertThat(result.getValuationHealthMessage()).isEqualTo("Legacy provenance retained");
        assertThat(result.getLines()).singleElement().satisfies(item -> {
            assertThat(item.getMaterialCode()).isEqualTo("MAT-10");
            assertThat(item.getWarehouseName()).isEqualTo("Central warehouse");
            assertThat(item.getStorageLocationCode()).isEqualTo("A-01-02");
            assertThat(item.getBatchCode()).isEqualTo("LOT-2026");
            assertThat(item.getCurrencyCode()).isEqualTo("USD");
            assertThat(item.getValuationHealth()).isEqualTo("PARTIAL");
            assertThat(item.getValuationHealthMessage()).isEqualTo("Legacy provenance retained");
        });
    }

    private NonMovingStockCaseMapper mapper() {
        return new NonMovingStockCaseMapper(
            materialRepository, warehouseRepository, storageLocationRepository, stockBatchRepository, userRepository
        );
    }

    private <T extends com.enterprise.mm.common.BusinessEntity> T base(T entity, UUID companyId, String code, String name) {
        entity.setTenantId("tenant-a");
        entity.setCompanyId(companyId);
        entity.setCode(code);
        entity.setName(name);
        entity.setStatus("ACTIVE");
        return entity;
    }
}
