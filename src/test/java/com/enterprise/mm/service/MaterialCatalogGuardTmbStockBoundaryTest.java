package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.enums.MaterialStatus;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.EquipmentRepository;
import com.enterprise.mm.repository.MaterialRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MaterialCatalogGuardTmbStockBoundaryTest {
    @Mock private MaterialRepository materialRepository;
    @Mock private EquipmentRepository equipmentRepository;

    private MaterialCatalogGuard guard;
    private UUID companyId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("tmb-stock-guard-test");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        guard = new MaterialCatalogGuard(materialRepository, equipmentRepository, new com.enterprise.mm.security.pilot.PilotCapabilityPolicy("SERIALIZED_EXECUTION,FIXED_ASSET_EXECUTION,SERVICE_EXECUTION"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void activeTmbMaterialIsAcceptedForStockMutation() {
        UUID materialId = UUID.randomUUID();
        when(materialRepository.findByIdAndIsDeletedFalse(materialId))
            .thenReturn(Optional.of(material(materialId, MaterialType.TMB)));

        assertThatCode(() -> guard.requireActiveTmbMaterial(materialId, "materialId"))
            .doesNotThrowAnyException();
    }

    @Test
    void fixedAssetMaterialIsRejectedForStockMutationEvenWhenActive() {
        UUID materialId = UUID.randomUUID();
        when(materialRepository.findByIdAndIsDeletedFalse(materialId))
            .thenReturn(Optional.of(material(materialId, MaterialType.FIXED_ASSET)));

        assertThatThrownBy(() -> guard.requireActiveTmbMaterial(materialId, "materialId"))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(((BusinessException) ex).getMessageKey())
                .isEqualTo("FIXED_ASSET_STOCK_FORBIDDEN"));
    }

    @Test
    void serviceMaterialIsRejectedForStockMutationEvenWhenActive() {
        UUID materialId = UUID.randomUUID();
        when(materialRepository.findByIdAndIsDeletedFalse(materialId))
            .thenReturn(Optional.of(material(materialId, MaterialType.SERVICES)));

        assertThatThrownBy(() -> guard.requireActiveTmbMaterial(materialId, "materialId"))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(((BusinessException) ex).getMessageKey())
                .isEqualTo("TMB_STOCK_ONLY"));
    }

    private Material material(UUID id, MaterialType materialType) {
        Material material = new Material();
        material.setId(id);
        material.setTenantId(TenantContext.currentTenant());
        material.setCompanyId(companyId);
        material.setCode("MAT-" + id.toString().substring(0, 8));
        material.setName("Material " + materialType);
        material.setStatus(MaterialStatus.ACTIVE.name());
        material.setMaterialType(materialType);
        return material;
    }
}
