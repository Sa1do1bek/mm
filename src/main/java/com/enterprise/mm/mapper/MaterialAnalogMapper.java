package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.MaterialAnalogResponse;
import com.enterprise.mm.payload.MaterialAnalogUpdateRequest;
import com.enterprise.mm.payload.MaterialAnalogCreateRequest;
import com.enterprise.mm.entity.MaterialAnalog;
import org.springframework.stereotype.Component;

@Component
public class MaterialAnalogMapper {
    public MaterialAnalogResponse toResponse(MaterialAnalog entity) {
        if (entity == null) {
            return null;
        }
        MaterialAnalogResponse dto = new MaterialAnalogResponse();
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
        dto.setAnalogMaterialId(entity.getAnalogMaterialId());
        dto.setAnalogType(entity.getAnalogType());
        dto.setInterchangeability(entity.getInterchangeability());
        dto.setConversionFactor(entity.getConversionFactor());
        dto.setPriority(entity.getPriority());
        dto.setApprovedBy(entity.getApprovedBy());
        dto.setValidFrom(entity.getValidFrom());
        dto.setValidTo(entity.getValidTo());
        return dto;
    }

    public MaterialAnalog toEntity(MaterialAnalogCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        MaterialAnalog entity = new MaterialAnalog();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setMaterialId(dto.getMaterialId());
        entity.setAnalogMaterialId(dto.getAnalogMaterialId());
        entity.setAnalogType(dto.getAnalogType());
        entity.setInterchangeability(dto.getInterchangeability());
        entity.setConversionFactor(dto.getConversionFactor());
        entity.setPriority(dto.getPriority());
        entity.setApprovedBy(dto.getApprovedBy());
        entity.setValidFrom(dto.getValidFrom());
        entity.setValidTo(dto.getValidTo());
        return entity;
    }

    public void updateEntity(MaterialAnalog entity, MaterialAnalogUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setMaterialId(dto.getMaterialId());
        entity.setAnalogMaterialId(dto.getAnalogMaterialId());
        entity.setAnalogType(dto.getAnalogType());
        entity.setInterchangeability(dto.getInterchangeability());
        entity.setConversionFactor(dto.getConversionFactor());
        entity.setPriority(dto.getPriority());
        entity.setApprovedBy(dto.getApprovedBy());
        entity.setValidFrom(dto.getValidFrom());
        entity.setValidTo(dto.getValidTo());
    }
}