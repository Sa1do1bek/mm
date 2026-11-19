package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.StorageBin;
import com.enterprise.mm.payload.StorageBinCreateRequest;
import com.enterprise.mm.payload.StorageBinResponse;
import com.enterprise.mm.payload.StorageBinUpdateRequest;
import org.springframework.stereotype.Component;

@Component
public class StorageBinMapper {
    public StorageBinResponse toResponse(StorageBin entity) {
        if (entity == null) {
            return null;
        }
        StorageBinResponse dto = new StorageBinResponse();
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
        dto.setStorageLocationId(entity.getStorageLocationId());
        dto.setStorageLocationCode(BaseMapper.code(entity.getStorageLocation()));
        dto.setStorageLocationName(BaseMapper.name(entity.getStorageLocation()));
        dto.setBinCode(entity.getBinCode());
        dto.setBarcode(entity.getBarcode());
        dto.setQrCode(entity.getQrCode());
        dto.setRfidTag(entity.getRfidTag());
        dto.setCapacityQuantity(entity.getCapacityQuantity());
        dto.setCapacityUomId(entity.getCapacityUomId());
        dto.setCapacityUomCode(BaseMapper.code(entity.getCapacityUom()));
        dto.setCapacityUomLabel(entity.getCapacityUom() == null ? null :
            entity.getCapacityUom().getMeasurementNameShort() == null || entity.getCapacityUom().getMeasurementNameShort().isBlank()
                ? BaseMapper.code(entity.getCapacityUom()) : entity.getCapacityUom().getMeasurementNameShort());
        dto.setMaxWeight(entity.getMaxWeight());
        dto.setAllowMixedMaterials(entity.isAllowMixedMaterials());
        return dto;
    }

    public StorageBin toEntity(StorageBinCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        StorageBin entity = new StorageBin();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setStorageLocationId(dto.getStorageLocationId());
        entity.setBinCode(dto.getBinCode());
        entity.setBarcode(dto.getBarcode());
        entity.setQrCode(dto.getQrCode());
        entity.setRfidTag(dto.getRfidTag());
        entity.setCapacityQuantity(dto.getCapacityQuantity());
        entity.setCapacityUomId(dto.getCapacityUomId());
        entity.setMaxWeight(dto.getMaxWeight());
        entity.setAllowMixedMaterials(dto.isAllowMixedMaterials());
        return entity;
    }

    public void updateEntity(StorageBin entity, StorageBinUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setStorageLocationId(dto.getStorageLocationId());
        entity.setBinCode(dto.getBinCode());
        entity.setBarcode(dto.getBarcode());
        entity.setQrCode(dto.getQrCode());
        entity.setRfidTag(dto.getRfidTag());
        entity.setCapacityQuantity(dto.getCapacityQuantity());
        entity.setCapacityUomId(dto.getCapacityUomId());
        if (dto.getMaxWeight() != null) {
            entity.setMaxWeight(dto.getMaxWeight());
        }
        entity.setAllowMixedMaterials(dto.isAllowMixedMaterials());
    }
}
