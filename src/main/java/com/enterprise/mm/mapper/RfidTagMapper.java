package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.RfidTagResponse;
import com.enterprise.mm.payload.RfidTagUpdateRequest;
import com.enterprise.mm.payload.RfidTagCreateRequest;
import com.enterprise.mm.entity.RfidTag;
import org.springframework.stereotype.Component;

@Component
public class RfidTagMapper {
    public RfidTagResponse toResponse(RfidTag entity) {
        if (entity == null) {
            return null;
        }
        RfidTagResponse dto = new RfidTagResponse();
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
        dto.setTagValue(entity.getTagValue());
        dto.setEntityType(entity.getEntityType());
        dto.setEntityId(entity.getEntityId());
        dto.setDeviceId(entity.getDeviceId());
        dto.setLastSeenAt(entity.getLastSeenAt());
        return dto;
    }

    public RfidTag toEntity(RfidTagCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        RfidTag entity = new RfidTag();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setTagValue(dto.getTagValue());
        entity.setEntityType(dto.getEntityType());
        entity.setEntityId(dto.getEntityId());
        entity.setDeviceId(dto.getDeviceId());
        entity.setLastSeenAt(dto.getLastSeenAt());
        return entity;
    }

    public void updateEntity(RfidTag entity, RfidTagUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setTagValue(dto.getTagValue());
        entity.setEntityType(dto.getEntityType());
        entity.setEntityId(dto.getEntityId());
        entity.setDeviceId(dto.getDeviceId());
        entity.setLastSeenAt(dto.getLastSeenAt());
    }
}