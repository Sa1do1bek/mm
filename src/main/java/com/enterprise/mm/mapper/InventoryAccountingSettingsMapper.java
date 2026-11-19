package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.InventoryAccountingSettings;
import com.enterprise.mm.payload.InventoryAccountingSettingsCreateRequest;
import com.enterprise.mm.payload.InventoryAccountingSettingsResponse;
import com.enterprise.mm.payload.InventoryAccountingSettingsUpdateRequest;
import org.springframework.stereotype.Component;

@Component
public class InventoryAccountingSettingsMapper {
    public InventoryAccountingSettingsResponse toResponse(InventoryAccountingSettings entity) {
        if (entity == null) {
            return null;
        }
        InventoryAccountingSettingsResponse dto = new InventoryAccountingSettingsResponse();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setCompanyId(entity.getCompanyId());
        dto.setCode(entity.getCode());
        dto.setName(entity.getName());
        dto.setStatus(entity.getStatus());
        dto.setDescription(entity.getDescription());
        dto.setDeleted(entity.isDeleted());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setUpdatedBy(entity.getUpdatedBy());
        dto.setVersion(entity.getVersion());
        dto.setDefaultValuationMethod(entity.getDefaultValuationMethod());
        dto.setDefaultStockAccountId(entity.getDefaultStockAccountId());
        dto.setDefaultStockAccountCode(entity.getDefaultStockAccount() == null ? null : entity.getDefaultStockAccount().getAccountCode());
        dto.setDefaultStockAccountName(entity.getDefaultStockAccount() == null ? null : entity.getDefaultStockAccount().getName());
        dto.setReturnablePackagingStockAccountId(entity.getReturnablePackagingStockAccountId());
        dto.setReturnablePackagingStockAccountCode(entity.getReturnablePackagingStockAccount() == null ? null : entity.getReturnablePackagingStockAccount().getAccountCode());
        dto.setReturnablePackagingStockAccountName(entity.getReturnablePackagingStockAccount() == null ? null : entity.getReturnablePackagingStockAccount().getName());
        dto.setAllowWarehouseValuationOverride(entity.isAllowWarehouseValuationOverride());
        dto.setAllowNegativeStock(entity.isAllowNegativeStock());
        dto.setAllowZeroCostReceipt(entity.isAllowZeroCostReceipt());
        dto.setPeriodCloseMode(entity.getPeriodCloseMode());
        dto.setBaseCurrencyCode(entity.getBaseCurrencyCode());
        dto.setRoundingScale(entity.getRoundingScale());
        dto.setRoundingMode(entity.getRoundingMode());
        dto.setRevaluationPostingMode(entity.getRevaluationPostingMode());
        dto.setImpairmentPostingMode(entity.getImpairmentPostingMode());
        return dto;
    }

    public InventoryAccountingSettings toEntity(InventoryAccountingSettingsCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        InventoryAccountingSettings entity = new InventoryAccountingSettings();
        updateCommon(entity, dto);
        return entity;
    }

    public void updateEntity(InventoryAccountingSettings entity, InventoryAccountingSettingsUpdateRequest dto) {
        updateCommon(entity, dto);
    }

    private void updateCommon(InventoryAccountingSettings entity, InventoryAccountingSettingsCreateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setDefaultValuationMethod(dto.getDefaultValuationMethod());
        entity.setDefaultStockAccountId(dto.getDefaultStockAccountId());
        entity.setReturnablePackagingStockAccountId(dto.getReturnablePackagingStockAccountId());
        entity.setAllowWarehouseValuationOverride(dto.isAllowWarehouseValuationOverride());
        entity.setAllowNegativeStock(dto.isAllowNegativeStock());
        entity.setAllowZeroCostReceipt(dto.isAllowZeroCostReceipt());
        entity.setPeriodCloseMode(dto.getPeriodCloseMode());
        entity.setBaseCurrencyCode(dto.getBaseCurrencyCode());
        entity.setRoundingScale(dto.getRoundingScale() == null ? 0 : dto.getRoundingScale());
        entity.setRoundingMode(dto.getRoundingMode());
        entity.setRevaluationPostingMode(dto.getRevaluationPostingMode());
        entity.setImpairmentPostingMode(dto.getImpairmentPostingMode());
    }
}
