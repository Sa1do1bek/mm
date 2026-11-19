package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.InventoryAccountingSettings;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.repository.MaterialRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InventoryValuationPolicyServiceTest {
    @Mock
    private InventoryAccountingSettingsService settingsService;
    @Mock
    private MaterialRepository materialRepository;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void resolveMethodUsesSettingsDefaultWhenOverridesAreDisabled() {
        UUID materialId = UUID.randomUUID();
        InventoryAccountingSettings settings = settings(String.valueOf(ValuationMethod.FIFO), false);
        when(settingsService.findOrDefault()).thenReturn(settings);

        ValuationMethod method = service().resolveMethod(materialId, UUID.randomUUID());

        assertThat(method).isEqualTo(ValuationMethod.FIFO);
        verify(materialRepository, never()).findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), materialId);
    }

    @Test
    void resolveMethodUsesMaterialMethodOnlyWhenOverridesAreEnabled() {
        UUID materialId = UUID.randomUUID();
        Material material = new Material();
        material.setId(materialId);
        material.setValuationMethod(com.enterprise.mm.enums.ValuationMethod.WEIGHTED_AVERAGE);
        when(settingsService.findOrDefault()).thenReturn(settings(ValuationMethod.FIFO.name(), true));
        when(materialRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), materialId))
            .thenReturn(Optional.of(material));

        ValuationMethod method = service().resolveMethod(materialId, UUID.randomUUID());

        assertThat(method).isEqualTo(ValuationMethod.WEIGHTED_AVERAGE);
    }

    private InventoryValuationPolicyService service() {
        return new InventoryValuationPolicyService(settingsService, materialRepository);
    }

    private static InventoryAccountingSettings settings(String defaultMethod, boolean allowOverride) {
        InventoryAccountingSettings settings = new InventoryAccountingSettings();
        settings.setDefaultValuationMethod(defaultMethod);
        settings.setAllowWarehouseValuationOverride(allowOverride);
        return settings;
    }
}
