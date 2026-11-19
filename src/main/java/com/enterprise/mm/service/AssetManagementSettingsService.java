package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AssetManagementSettings;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.AssetFoundationMapper;
import com.enterprise.mm.payload.AssetSettingsResponse;
import com.enterprise.mm.payload.AssetSettingsUpdateRequest;
import com.enterprise.mm.repository.AssetManagementSettingsRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AssetManagementSettingsService {
    private final AssetManagementSettingsRepository repository;
    private final WarehouseRepository warehouseRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final AssetFoundationMapper mapper;

    @Transactional(readOnly = true)
    public AssetSettingsResponse get() {
        return repository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany())
            .map(mapper::toResponse)
            .orElse(null);
    }

    @Transactional
    public AssetSettingsResponse update(AssetSettingsUpdateRequest request) {
        if (request == null || request.centralWarehouseId() == null) {
            throw new BadRequestException(
                "Central warehouse is required",
                "ASSET_CENTRAL_WAREHOUSE_NOT_CONFIGURED"
            );
        }
        validateWarehouse(request.centralWarehouseId());
        validateStorageLocation(request.centralStorageLocationId(), request.centralWarehouseId());
        AssetManagementSettings settings = repository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany())
            .orElseGet(this::newSettings);
        settings.setCentralWarehouseId(request.centralWarehouseId());
        settings.setCentralStorageLocationId(request.centralStorageLocationId());
        settings.setInventoryNumberPrefix(normalizePrefix(request.inventoryNumberPrefix()));
        settings.setInventoryNumberNextValue(request.inventoryNumberNextValue() == null
            ? 1L
            : request.inventoryNumberNextValue());
        settings.setInventoryNumberPadding(request.inventoryNumberPadding() == null
            ? 6
            : request.inventoryNumberPadding());
        settings.setAutoNumberAtReceipt(request.autoNumberAtReceipt());
        settings.setRequireSerialNumber(request.requireSerialNumber());
        return mapper.toResponse(repository.saveAndFlush(settings));
    }

    @Transactional(readOnly = true)
    public AssetManagementSettings requireConfigured() {
        return repository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany())
            .filter(settings -> settings.getCentralWarehouseId() != null)
            .orElseThrow(() -> new BusinessException(
                "Central warehouse is not configured for fixed assets",
                "ASSET_CENTRAL_WAREHOUSE_NOT_CONFIGURED"
            ));
    }

    private AssetManagementSettings newSettings() {
        AssetManagementSettings settings = new AssetManagementSettings();
        settings.setCode("ASSET-SETTINGS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        settings.setName("Fixed asset settings");
        settings.setStatus("ACTIVE");
        settings.setCompanyId(CompanyContext.currentCompany());
        return settings;
    }

    private void validateWarehouse(UUID warehouseId) {
        warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouseId)
            .orElseThrow(() -> new BadRequestException(
                "Central warehouse must belong to the current company",
                "ASSET_SCOPE_DENIED",
                warehouseId
            ));
    }

    private void validateStorageLocation(UUID storageLocationId, UUID warehouseId) {
        if (storageLocationId == null) {
            return;
        }
        storageLocationRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), storageLocationId)
            .filter(location -> warehouseId.equals(location.getWarehouseId()))
            .orElseThrow(() -> new BadRequestException(
                "Central storage location must belong to the configured central warehouse",
                "ASSET_SCOPE_DENIED",
                storageLocationId
            ));
    }

    private String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "FA";
        }
        return prefix.trim().toUpperCase(Locale.ROOT);
    }
}
