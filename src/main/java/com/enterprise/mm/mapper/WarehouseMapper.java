package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.WarehouseResponse;
import com.enterprise.mm.payload.WarehouseUpdateRequest;
import com.enterprise.mm.payload.WarehouseCreateRequest;
import com.enterprise.mm.entity.Warehouse;
import org.springframework.stereotype.Component;

@Component
public class WarehouseMapper {
    public WarehouseResponse toResponse(Warehouse entity) {
        if (entity == null) {
            return null;
        }
        WarehouseResponse dto = new WarehouseResponse();
        dto.setId(entity.getId());
        dto.setSourceData(entity.getSourceData());
        dto.setParentId(entity.getParentId());
        dto.setSourceGroup(entity.isSourceGroup());
        dto.setSourceHasBalance(entity.isSourceHasBalance());
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
        dto.setAddress(entity.getAddress());
        return dto;
    }

    public Warehouse toEntity(WarehouseCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        Warehouse entity = new Warehouse();
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setAddress(dto.getAddress());
        return entity;
    }

    public void updateEntity(Warehouse entity, WarehouseUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setAddress(dto.getAddress());
    }
}
