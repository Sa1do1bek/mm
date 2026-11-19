package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.repository.FixedAssetRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.RfidTagRepository;
import com.enterprise.mm.repository.StockBatchRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WarehouseIdentificationAssetLookupTest {
    @AfterEach
    void clearScope() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void findsFixedAssetByInventoryNumberForCurrentCompany() {
        String tenantId = "warehouse-scan-test";
        UUID companyId = UUID.randomUUID();
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);
        FixedAsset asset = new FixedAsset();
        asset.setId(UUID.randomUUID());
        asset.setTenantId(tenantId);
        asset.setCompanyId(companyId);
        asset.setAssetCode("FA-42");
        asset.setInventoryNumber("INV-42");
        asset.setName("Laboratory pump");
        FixedAssetRepository fixedAssets = mock(FixedAssetRepository.class);
        when(fixedAssets.findByTenantIdAndCompanyIdAndInventoryNumberAndIsDeletedFalse(tenantId, companyId, "INV-42"))
            .thenReturn(Optional.of(asset));
        WarehouseIdentificationService service = new WarehouseIdentificationService(
            mock(MaterialRepository.class), fixedAssets, mock(StockBatchRepository.class),
            mock(StorageLocationRepository.class), mock(WarehouseRepository.class),
            mock(RfidTagRepository.class), mock(AuditService.class));

        var result = service.lookup("INV-42");

        assertThat(result.entityType()).isEqualTo("FIXED_ASSET");
        assertThat(result.entityId()).isEqualTo(asset.getId());
        assertThat(result.identifierType()).isEqualTo("INVENTORY_NUMBER");
        assertThat(result.name()).isEqualTo("Laboratory pump");
    }
}
