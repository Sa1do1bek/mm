package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.StorageLocationResponse;
import com.enterprise.mm.payload.StorageLocationUpdateRequest;
import com.enterprise.mm.payload.StorageLocationCreateRequest;
import com.enterprise.mm.entity.StorageLocation;
import org.springframework.stereotype.Component;

@Component
public class StorageLocationMapper {
    public StorageLocationResponse toResponse(StorageLocation entity) {
        if (entity == null) {
            return null;
        }
        StorageLocationResponse dto = new StorageLocationResponse();
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
        dto.setWarehouseId(entity.getWarehouseId());
        dto.setWarehouseCode(BaseMapper.code(entity.getWarehouse()));
        dto.setWarehouseName(BaseMapper.name(entity.getWarehouse()));
        dto.setZoneCode(entity.getZoneCode());
        dto.setBinCode(entity.getBinCode());
        dto.setBarcode(entity.getBarcode());
        dto.setQrCode(entity.getQrCode());
        dto.setRfidTag(entity.getRfidTag());
        dto.setStorageZoneType(entity.getStorageZoneType());
        dto.setTemperatureZone(entity.getTemperatureZone());
        dto.setHazardClassAllowed(entity.getHazardClassAllowed());
        dto.setCapacityQuantity(entity.getCapacityQuantity());
        dto.setMaxWeight(entity.getMaxWeight());
        dto.setAllowMixedMaterials(entity.isAllowMixedMaterials());
        return dto;
    }

    public StorageLocation toEntity(StorageLocationCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        StorageLocation entity = new StorageLocation();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setBinCode(dto.getBinCode());
        entity.setBarcode(dto.getBarcode());
        entity.setQrCode(dto.getQrCode());
        entity.setRfidTag(dto.getRfidTag());
        entity.setStorageZoneType(dto.getStorageZoneType());
        entity.setTemperatureZone(dto.getTemperatureZone());
        entity.setHazardClassAllowed(dto.getHazardClassAllowed());
        entity.setCapacityQuantity(dto.getCapacityQuantity());
        entity.setMaxWeight(dto.getMaxWeight());
        entity.setAllowMixedMaterials(dto.isAllowMixedMaterials());
        return entity;
    }

    public void updateEntity(StorageLocation entity, StorageLocationUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setBinCode(dto.getBinCode());
        entity.setBarcode(dto.getBarcode());
        entity.setQrCode(dto.getQrCode());
        entity.setRfidTag(dto.getRfidTag());
        entity.setStorageZoneType(dto.getStorageZoneType());
        entity.setTemperatureZone(dto.getTemperatureZone());
        entity.setHazardClassAllowed(dto.getHazardClassAllowed());
        entity.setCapacityQuantity(dto.getCapacityQuantity());
        entity.setMaxWeight(dto.getMaxWeight());
        entity.setAllowMixedMaterials(dto.isAllowMixedMaterials());
    }
}
