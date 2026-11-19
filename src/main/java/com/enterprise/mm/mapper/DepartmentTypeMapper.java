package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.DepartmentTypeResponse;
import com.enterprise.mm.payload.DepartmentTypeUpdateRequest;
import com.enterprise.mm.payload.DepartmentTypeCreateRequest;
import com.enterprise.mm.entity.DepartmentType;
import org.springframework.stereotype.Component;

@Component
public class DepartmentTypeMapper {
    public DepartmentTypeResponse toResponse(DepartmentType entity) {
        if (entity == null) {
            return null;
        }
        DepartmentTypeResponse dto = new DepartmentTypeResponse();
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
        dto.setLevelCode(entity.getLevelCode());
        dto.setHierarchyLevel(entity.getHierarchyLevel());
        dto.setSystemType(entity.isSystemType());
        return dto;
    }

    public DepartmentType toEntity(DepartmentTypeCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        DepartmentType entity = new DepartmentType();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setLevelCode(dto.getLevelCode());
        entity.setHierarchyLevel(dto.getHierarchyLevel());
        entity.setSystemType(dto.isSystemType());
        return entity;
    }

    public void updateEntity(DepartmentType entity, DepartmentTypeUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setLevelCode(dto.getLevelCode());
        entity.setHierarchyLevel(dto.getHierarchyLevel());
        entity.setSystemType(dto.isSystemType());
    }
}