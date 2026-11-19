package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.Role;
import com.enterprise.mm.payload.RoleCreateRequest;
import com.enterprise.mm.payload.RoleResponse;
import com.enterprise.mm.payload.RoleUpdateRequest;
import org.springframework.stereotype.Component;

@Component
public class RoleMapper {
    public RoleResponse toResponse(Role entity) {
        if (entity == null) {
            return null;
        }
        RoleResponse dto = new RoleResponse();
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
        dto.setRoleName(entity.getRoleName());
        dto.setGlobalRole(entity.getCompanyId() == null);
        return dto;
    }

    public Role toEntity(RoleCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        Role entity = new Role();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setRoleName(dto.getRoleName());
        return entity;
    }

    public void updateEntity(Role entity, RoleUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setRoleName(dto.getRoleName());
    }
}
