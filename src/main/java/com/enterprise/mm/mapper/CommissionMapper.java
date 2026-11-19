package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.Commission;
import com.enterprise.mm.payload.CommissionCreateRequest;
import com.enterprise.mm.payload.CommissionResponse;
import com.enterprise.mm.payload.CommissionUpdateRequest;
import org.springframework.stereotype.Component;

@Component
public class CommissionMapper {
    public CommissionResponse toResponse(Commission entity) {
        if (entity == null) {
            return null;
        }
        CommissionResponse dto = new CommissionResponse();
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
        dto.setType(entity.getType());
        dto.setValidFrom(entity.getValidFrom());
        dto.setValidTo(entity.getValidTo());
        dto.setActive(entity.isActive());
        return dto;
    }

    public Commission toEntity(CommissionCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        Commission entity = new Commission();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setCompanyId(dto.getCompanyId());
        entity.setType(dto.getType());
        entity.setValidFrom(dto.getValidFrom());
        entity.setValidTo(dto.getValidTo());
        if (dto.getActive() != null) {
            entity.setActive(dto.getActive());
        }
        return entity;
    }

    public void updateEntity(Commission entity, CommissionUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setType(dto.getType());
        entity.setValidFrom(dto.getValidFrom());
        entity.setValidTo(dto.getValidTo());
        if (dto.getActive() != null) {
            entity.setActive(dto.getActive());
        }
    }
}
