package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.ChartOfAccountResponse;
import com.enterprise.mm.payload.ChartOfAccountUpdateRequest;
import com.enterprise.mm.payload.ChartOfAccountCreateRequest;
import com.enterprise.mm.entity.ChartOfAccount;
import org.springframework.stereotype.Component;

@Component
public class ChartOfAccountMapper {
    public ChartOfAccountResponse toResponse(ChartOfAccount entity) {
        if (entity == null) {
            return null;
        }
        ChartOfAccountResponse dto = new ChartOfAccountResponse();
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
        dto.setAccountCode(entity.getAccountCode());
        dto.setParentCode(entity.getParentCode());
        dto.setAccountType(entity.getAccountType());
        dto.setGroup(entity.isGroup());
        dto.setSourceSystem(entity.getSourceSystem());
        dto.setExternalId(entity.getExternalId());
        dto.setLastSyncedAt(entity.getLastSyncedAt());
        return dto;
    }

    public ChartOfAccount toEntity(ChartOfAccountCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        ChartOfAccount entity = new ChartOfAccount();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setAccountCode(dto.getAccountCode());
        entity.setParentCode(dto.getParentCode());
        entity.setAccountType(dto.getAccountType());
        entity.setGroup(dto.isGroup());
        entity.setSourceSystem(dto.getSourceSystem());
        entity.setExternalId(dto.getExternalId());
        entity.setLastSyncedAt(dto.getLastSyncedAt());
        return entity;
    }

    public void updateEntity(ChartOfAccount entity, ChartOfAccountUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setAccountCode(dto.getAccountCode());
        entity.setParentCode(dto.getParentCode());
        entity.setAccountType(dto.getAccountType());
        entity.setGroup(dto.isGroup());
        entity.setSourceSystem(dto.getSourceSystem());
        entity.setExternalId(dto.getExternalId());
        entity.setLastSyncedAt(dto.getLastSyncedAt());
    }
}