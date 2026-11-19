package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.ExternalInboundRecordResponse;
import com.enterprise.mm.payload.ExternalInboundRecordUpdateRequest;
import com.enterprise.mm.payload.ExternalInboundRecordCreateRequest;
import com.enterprise.mm.entity.ExternalInboundRecord;
import org.springframework.stereotype.Component;

@Component
public class ExternalInboundRecordMapper {
    public ExternalInboundRecordResponse toResponse(ExternalInboundRecord entity) {
        if (entity == null) {
            return null;
        }
        ExternalInboundRecordResponse dto = new ExternalInboundRecordResponse();
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
        dto.setSourceSystem(entity.getSourceSystem());
        dto.setDataset(entity.getDataset());
        dto.setExternalId(entity.getExternalId());
        dto.setExternalCode(entity.getExternalCode());
        dto.setProviderStatus(entity.getProviderStatus());
        dto.setSourcePath(entity.getSourcePath());
        dto.setPayloadHash(entity.getPayloadHash());
        dto.setPayload(entity.getPayload());
        dto.setAppliedEntityType(entity.getAppliedEntityType());
        dto.setAppliedEntityId(entity.getAppliedEntityId());
        dto.setSyncedAt(entity.getSyncedAt());
        return dto;
    }

    public ExternalInboundRecord toEntity(ExternalInboundRecordCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        ExternalInboundRecord entity = new ExternalInboundRecord();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setSourceSystem(dto.getSourceSystem());
        entity.setDataset(dto.getDataset());
        entity.setExternalId(dto.getExternalId());
        entity.setExternalCode(dto.getExternalCode());
        entity.setProviderStatus(dto.getProviderStatus());
        entity.setSourcePath(dto.getSourcePath());
        entity.setPayloadHash(dto.getPayloadHash());
        entity.setPayload(dto.getPayload());
        entity.setAppliedEntityType(dto.getAppliedEntityType());
        entity.setAppliedEntityId(dto.getAppliedEntityId());
        entity.setSyncedAt(dto.getSyncedAt());
        return entity;
    }

    public void updateEntity(ExternalInboundRecord entity, ExternalInboundRecordUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setSourceSystem(dto.getSourceSystem());
        entity.setDataset(dto.getDataset());
        entity.setExternalId(dto.getExternalId());
        entity.setExternalCode(dto.getExternalCode());
        entity.setProviderStatus(dto.getProviderStatus());
        entity.setSourcePath(dto.getSourcePath());
        entity.setPayloadHash(dto.getPayloadHash());
        entity.setPayload(dto.getPayload());
        entity.setAppliedEntityType(dto.getAppliedEntityType());
        entity.setAppliedEntityId(dto.getAppliedEntityId());
        entity.setSyncedAt(dto.getSyncedAt());
    }
}