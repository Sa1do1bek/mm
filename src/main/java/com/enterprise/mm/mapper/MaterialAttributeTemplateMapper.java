package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.MaterialAttributeTemplate;
import com.enterprise.mm.payload.MaterialAttributeTemplateCreateRequest;
import com.enterprise.mm.payload.MaterialAttributeTemplateResponse;
import com.enterprise.mm.payload.MaterialAttributeTemplateUpdateRequest;
import org.springframework.stereotype.Component;

@Component
public class MaterialAttributeTemplateMapper {
    public MaterialAttributeTemplateResponse toResponse(MaterialAttributeTemplate entity) {
        if (entity == null) {
            return null;
        }
        MaterialAttributeTemplateResponse dto = new MaterialAttributeTemplateResponse();
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
        dto.setMaterialGroupId(entity.getMaterialGroupId());
        dto.setAttributeName(entity.getAttributeName());
        dto.setRequired(entity.getRequired());
        dto.setDataType(entity.getDataType());
        dto.setAllowedValues(entity.getAllowedValues());
        return dto;
    }

    public MaterialAttributeTemplate toEntity(MaterialAttributeTemplateCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        MaterialAttributeTemplate entity = new MaterialAttributeTemplate();
        updateEntity(entity, dto);
        return entity;
    }

    public void updateEntity(MaterialAttributeTemplate entity, MaterialAttributeTemplateCreateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setMaterialGroupId(dto.getMaterialGroupId());
        entity.setAttributeName(dto.getAttributeName());
        entity.setRequired(dto.getRequired());
        entity.setDataType(dto.getDataType());
        entity.setAllowedValues(dto.getAllowedValues());
    }

    public void updateEntity(MaterialAttributeTemplate entity, MaterialAttributeTemplateUpdateRequest dto) {
        updateEntity(entity, (MaterialAttributeTemplateCreateRequest) dto);
    }
}
