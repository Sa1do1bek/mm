package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.IkpuCodeResponse;
import com.enterprise.mm.payload.IkpuCodeUpdateRequest;
import com.enterprise.mm.payload.IkpuCodeCreateRequest;
import com.enterprise.mm.entity.IkpuCode;
import org.springframework.stereotype.Component;

@Component
@lombok.RequiredArgsConstructor
public class IkpuCodeMapper {
    private final com.enterprise.mm.service.UnitOfMeasureDisplayService unitDisplay;
    public IkpuCodeResponse toResponse(IkpuCode entity) {
        if (entity == null) {
            return null;
        }
        IkpuCodeResponse dto = new IkpuCodeResponse();
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
        dto.setIkpuCode(entity.getIkpuCode());
        dto.setPackageCode(entity.getPackageCode());
        dto.setUnitCode(entity.getUnitCode());
        dto.setUnitShortName(unitDisplay.shortName(entity, null, entity.getUnitCode()));
        dto.setGroupCode(entity.getGroupCode());
        dto.setVatRate(entity.getVatRate());
        dto.setService(entity.isService());
        dto.setSourceSystem(entity.getSourceSystem());
        dto.setExternalId(entity.getExternalId());
        dto.setLastSyncedAt(entity.getLastSyncedAt());
        return dto;
    }

    public IkpuCode toEntity(IkpuCodeCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        IkpuCode entity = new IkpuCode();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setIkpuCode(dto.getIkpuCode());
        entity.setPackageCode(dto.getPackageCode());
        entity.setUnitCode(dto.getUnitCode());
        entity.setGroupCode(dto.getGroupCode());
        entity.setVatRate(dto.getVatRate());
        entity.setService(dto.isService());
        entity.setSourceSystem(dto.getSourceSystem());
        entity.setExternalId(dto.getExternalId());
        entity.setLastSyncedAt(dto.getLastSyncedAt());
        return entity;
    }

    public void updateEntity(IkpuCode entity, IkpuCodeUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setIkpuCode(dto.getIkpuCode());
        entity.setPackageCode(dto.getPackageCode());
        entity.setUnitCode(dto.getUnitCode());
        entity.setGroupCode(dto.getGroupCode());
        entity.setVatRate(dto.getVatRate());
        entity.setService(dto.isService());
        entity.setSourceSystem(dto.getSourceSystem());
        entity.setExternalId(dto.getExternalId());
        entity.setLastSyncedAt(dto.getLastSyncedAt());
    }
}