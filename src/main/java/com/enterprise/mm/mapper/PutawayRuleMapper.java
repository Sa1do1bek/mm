package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.PutawayRuleResponse;
import com.enterprise.mm.payload.PutawayRuleUpdateRequest;
import com.enterprise.mm.payload.PutawayRuleCreateRequest;
import com.enterprise.mm.entity.PutawayRule;
import org.springframework.stereotype.Component;

@Component
public class PutawayRuleMapper {
    public PutawayRuleResponse toResponse(PutawayRule entity) {
        if (entity == null) {
            return null;
        }
        PutawayRuleResponse dto = new PutawayRuleResponse();
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
        dto.setPriority(entity.getPriority());
        dto.setWarehouseId(entity.getWarehouseId());
        dto.setMaterialId(entity.getMaterialId());
        dto.setMaterialGroupId(entity.getMaterialGroupId());
        dto.setHazardClass(entity.getHazardClass());
        dto.setTemperatureZone(entity.getTemperatureZone());
        dto.setAbcClass(entity.getAbcClass());
        dto.setStorageZoneCode(entity.getStorageZoneCode());
        dto.setTargetStorageLocationId(entity.getTargetStorageLocationId());
        dto.setMaxQuantity(entity.getMaxQuantity());
        dto.setMinShelfLifeDays(entity.getMinShelfLifeDays());
        dto.setAllowMixedMaterials(entity.isAllowMixedMaterials());
        dto.setAllowCrossDock(entity.isAllowCrossDock());
        return dto;
    }

    public PutawayRule toEntity(PutawayRuleCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        PutawayRule entity = new PutawayRule();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setPriority(dto.getPriority());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setMaterialGroupId(dto.getMaterialGroupId());
        entity.setHazardClass(dto.getHazardClass());
        entity.setTemperatureZone(dto.getTemperatureZone());
        entity.setAbcClass(dto.getAbcClass());
        entity.setStorageZoneCode(dto.getStorageZoneCode());
        entity.setTargetStorageLocationId(dto.getTargetStorageLocationId());
        entity.setMaxQuantity(dto.getMaxQuantity());
        entity.setMinShelfLifeDays(dto.getMinShelfLifeDays());
        entity.setAllowMixedMaterials(dto.isAllowMixedMaterials());
        entity.setAllowCrossDock(dto.isAllowCrossDock());
        return entity;
    }

    public void updateEntity(PutawayRule entity, PutawayRuleUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setPriority(dto.getPriority());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setMaterialGroupId(dto.getMaterialGroupId());
        entity.setHazardClass(dto.getHazardClass());
        entity.setTemperatureZone(dto.getTemperatureZone());
        entity.setAbcClass(dto.getAbcClass());
        entity.setStorageZoneCode(dto.getStorageZoneCode());
        entity.setTargetStorageLocationId(dto.getTargetStorageLocationId());
        entity.setMaxQuantity(dto.getMaxQuantity());
        entity.setMinShelfLifeDays(dto.getMinShelfLifeDays());
        entity.setAllowMixedMaterials(dto.isAllowMixedMaterials());
        entity.setAllowCrossDock(dto.isAllowCrossDock());
    }
}