package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.StockBalanceResponse;
import com.enterprise.mm.payload.StockBalanceUpdateRequest;
import com.enterprise.mm.payload.StockBalanceCreateRequest;
import com.enterprise.mm.entity.StockBalance;
import org.springframework.stereotype.Component;

@Component
public class StockBalanceMapper {
    public StockBalanceResponse toResponse(StockBalance entity) {
        if (entity == null) {
            return null;
        }
        StockBalanceResponse dto = new StockBalanceResponse();
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
        dto.setMaterialId(entity.getMaterialId());
        if (entity.getMaterial() != null) {
            var material = entity.getMaterial();
            dto.setMaterialGroupId(material.getMaterialGroupId());
            dto.setUnitOfMeasureId(material.getUnitOfMeasureId());
            if (material.getMaterialGroup() != null) {
                dto.setMaterialGroupCode(material.getMaterialGroup().getCode());
                dto.setMaterialGroupName(material.getMaterialGroup().getName());
            }
        }
        if (entity.getStockBatch() != null) {
            dto.setManufacturedOn(entity.getStockBatch().getManufacturedOn());
            dto.setReceivedDate(entity.getStockBatch().getReceivedDate());
            dto.setManufacturerBatchNumber(entity.getStockBatch().getManufacturerBatchNumber());
        }
        dto.setMaterialCode(BaseMapper.materialCode(entity.getMaterial()));
        dto.setMaterialName(BaseMapper.materialName(entity.getMaterial()));
        dto.setWarehouseId(entity.getWarehouseId());
        dto.setWarehouseCode(BaseMapper.code(entity.getWarehouse()));
        dto.setWarehouseName(BaseMapper.name(entity.getWarehouse()));
        dto.setStorageLocationId(entity.getStorageLocationId());
        dto.setStorageLocationCode(BaseMapper.code(entity.getStorageLocation()));
        dto.setStorageLocationName(BaseMapper.name(entity.getStorageLocation()));
        dto.setStorageBinId(entity.getStorageBinId());
        dto.setStorageBinCode(BaseMapper.storageBinCode(entity.getStorageBin()));
        dto.setStorageBinName(BaseMapper.storageBinName(entity.getStorageBin()));
        dto.setStockBatchId(entity.getStockBatchId());
        dto.setStockBatchCode(BaseMapper.stockBatchCode(entity.getStockBatch()));
        dto.setStockBatchName(BaseMapper.stockBatchName(entity.getStockBatch()));
        dto.setStockBatchNumber(entity.getStockBatch() == null ? null : entity.getStockBatch().getBatchNumber());
        dto.setUnitCode(entity.getMaterial() == null || entity.getMaterial().getUnitOfMeasure() == null ? null : entity.getMaterial().getUnitOfMeasure().getCode());
        if (entity.getMaterial() != null && entity.getMaterial().getUnitOfMeasure() != null) {
            var unit = entity.getMaterial().getUnitOfMeasure();
            dto.setUnitLabel(com.enterprise.mm.common.UnitOfMeasureLabels.localizedShort(unit,
                org.springframework.context.i18n.LocaleContextHolder.getLocale()));
            if (entity.getMaterial().getUnitOfMeasureId() != null && !unit.isDeleted())
                dto.setQuantityScale(com.enterprise.mm.service.StockUomPolicy.quantityScale(entity.getMaterial()));
        }
        if (entity.getStockBatch() != null && entity.getStockBatch().getSerialNumber() != null
            && !entity.getStockBatch().getSerialNumber().isBlank()) dto.setQuantityScale(0);
        dto.setExpirationDate(entity.getStockBatch() == null ? null : entity.getStockBatch().getExpiresOn() == null ? entity.getStockBatch().getExpirationDate() : entity.getStockBatch().getExpiresOn());
        dto.setExecutableQuantity(entity.getAvailableQuantity() == null ? java.math.BigDecimal.ZERO : entity.getAvailableQuantity());
        dto.setUnavailableReason(com.enterprise.mm.service.StockPositionPolicy.unavailableReason(entity, dto.getExecutableQuantity()));
        dto.setQuantity(entity.getQuantity());
        dto.setAvailableQuantity(entity.getAvailableQuantity());
        dto.setReservedQuantity(entity.getReservedQuantity());
        dto.setQuarantineQuantity(entity.getQuarantineQuantity());
        dto.setTotalValue(entity.getTotalValue());
        dto.setBalanceAt(entity.getBalanceAt());
        dto.setOwnerPartyId(entity.getOwnerPartyId());
        dto.setStockCategory(entity.getStockCategory());
        return dto;
    }

    public StockBalance toEntity(StockBalanceCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        StockBalance entity = new StockBalance();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setStorageLocationId(dto.getStorageLocationId());
        entity.setStorageBinId(dto.getStorageBinId());
        entity.setStockBatchId(dto.getStockBatchId());
        entity.setQuantity(dto.getQuantity());
        entity.setAvailableQuantity(dto.getAvailableQuantity());
        entity.setReservedQuantity(dto.getReservedQuantity());
        entity.setQuarantineQuantity(dto.getQuarantineQuantity());
        entity.setTotalValue(dto.getTotalValue());
        entity.setBalanceAt(dto.getBalanceAt());
        return entity;
    }

    public void updateEntity(StockBalance entity, StockBalanceUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setStorageLocationId(dto.getStorageLocationId());
        entity.setStorageBinId(dto.getStorageBinId());
        entity.setStockBatchId(dto.getStockBatchId());
        entity.setQuantity(dto.getQuantity());
        entity.setAvailableQuantity(dto.getAvailableQuantity());
        entity.setReservedQuantity(dto.getReservedQuantity());
        entity.setQuarantineQuantity(dto.getQuarantineQuantity());
        entity.setTotalValue(dto.getTotalValue());
        entity.setBalanceAt(dto.getBalanceAt());
    }
}
