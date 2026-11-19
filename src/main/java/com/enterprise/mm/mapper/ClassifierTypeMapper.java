package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.ClassifierTypeResponse;
import com.enterprise.mm.payload.ClassifierTypeUpdateRequest;
import com.enterprise.mm.payload.ClassifierTypeCreateRequest;
import com.enterprise.mm.entity.ClassifierType;
import org.springframework.stereotype.Component;

@Component
public class ClassifierTypeMapper {
    public ClassifierTypeResponse toResponse(ClassifierType entity) {
        if (entity == null) {
            return null;
        }
        ClassifierTypeResponse dto = new ClassifierTypeResponse();
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
        dto.setModule(entity.getModule());
        dto.setSystemType(entity.isSystemType());
        return dto;
    }

    public ClassifierType toEntity(ClassifierTypeCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        ClassifierType entity = new ClassifierType();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setModule(dto.getModule());
        entity.setSystemType(dto.isSystemType());
        return entity;
    }

    public void updateEntity(ClassifierType entity, ClassifierTypeUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setModule(dto.getModule());
        entity.setSystemType(dto.isSystemType());
    }
}