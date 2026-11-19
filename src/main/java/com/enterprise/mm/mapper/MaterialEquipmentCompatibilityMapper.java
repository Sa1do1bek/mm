package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.MaterialEquipmentCompatibilityResponse;
import com.enterprise.mm.payload.MaterialEquipmentCompatibilityUpdateRequest;
import com.enterprise.mm.payload.MaterialEquipmentCompatibilityCreateRequest;
import com.enterprise.mm.entity.MaterialEquipmentCompatibility;
import org.springframework.stereotype.Component;

@Component
public class MaterialEquipmentCompatibilityMapper {
    public MaterialEquipmentCompatibilityResponse toResponse(MaterialEquipmentCompatibility entity) {
        if (entity == null) {
            return null;
        }
        MaterialEquipmentCompatibilityResponse dto = new MaterialEquipmentCompatibilityResponse();
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
        dto.setEquipmentId(entity.getEquipmentId());
        dto.setCompatibilityType(entity.getCompatibilityType());
        dto.setManufacturerPartNo(entity.getManufacturerPartNo());
        dto.setRequired(entity.isRequired());
        dto.setValidFrom(entity.getValidFrom());
        dto.setValidTo(entity.getValidTo());
        return dto;
    }

    public MaterialEquipmentCompatibility toEntity(MaterialEquipmentCompatibilityCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        MaterialEquipmentCompatibility entity = new MaterialEquipmentCompatibility();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setMaterialId(dto.getMaterialId());
        entity.setEquipmentId(dto.getEquipmentId());
        entity.setCompatibilityType(dto.getCompatibilityType());
        entity.setManufacturerPartNo(dto.getManufacturerPartNo());
        entity.setRequired(dto.isRequired());
        entity.setValidFrom(dto.getValidFrom());
        entity.setValidTo(dto.getValidTo());
        return entity;
    }

    public void updateEntity(MaterialEquipmentCompatibility entity, MaterialEquipmentCompatibilityUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setMaterialId(dto.getMaterialId());
        entity.setEquipmentId(dto.getEquipmentId());
        entity.setCompatibilityType(dto.getCompatibilityType());
        entity.setManufacturerPartNo(dto.getManufacturerPartNo());
        entity.setRequired(dto.isRequired());
        entity.setValidFrom(dto.getValidFrom());
        entity.setValidTo(dto.getValidTo());
    }
}