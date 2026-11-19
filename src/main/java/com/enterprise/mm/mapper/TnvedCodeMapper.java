package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.TnvedCodeResponse;
import com.enterprise.mm.payload.TnvedCodeUpdateRequest;
import com.enterprise.mm.payload.TnvedCodeCreateRequest;
import com.enterprise.mm.entity.TnvedCode;
import org.springframework.stereotype.Component;

@Component
@lombok.RequiredArgsConstructor
public class TnvedCodeMapper {
    private final com.enterprise.mm.service.UnitOfMeasureDisplayService unitDisplay;
    public TnvedCodeResponse toResponse(TnvedCode entity) {
        if (entity == null) {
            return null;
        }
        TnvedCodeResponse dto = new TnvedCodeResponse();
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
        dto.setTnvedCode(entity.getTnvedCode());
        dto.setParentCode(entity.getParentCode());
        dto.setSectionCode(entity.getSectionCode());
        dto.setUnitCode(entity.getUnitCode());
        dto.setUnitShortName(unitDisplay.shortName(entity, null, entity.getUnitCode()));
        dto.setDutyRate(entity.getDutyRate());
        dto.setVatRate(entity.getVatRate());
        dto.setSourceSystem(entity.getSourceSystem());
        dto.setExternalId(entity.getExternalId());
        dto.setLastSyncedAt(entity.getLastSyncedAt());
        return dto;
    }

    public TnvedCode toEntity(TnvedCodeCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        TnvedCode entity = new TnvedCode();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setTnvedCode(dto.getTnvedCode());
        entity.setParentCode(dto.getParentCode());
        entity.setSectionCode(dto.getSectionCode());
        entity.setUnitCode(dto.getUnitCode());
        entity.setDutyRate(dto.getDutyRate());
        entity.setVatRate(dto.getVatRate());
        entity.setSourceSystem(dto.getSourceSystem());
        entity.setExternalId(dto.getExternalId());
        entity.setLastSyncedAt(dto.getLastSyncedAt());
        return entity;
    }

    public void updateEntity(TnvedCode entity, TnvedCodeUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setTnvedCode(dto.getTnvedCode());
        entity.setParentCode(dto.getParentCode());
        entity.setSectionCode(dto.getSectionCode());
        entity.setUnitCode(dto.getUnitCode());
        entity.setDutyRate(dto.getDutyRate());
        entity.setVatRate(dto.getVatRate());
        entity.setSourceSystem(dto.getSourceSystem());
        entity.setExternalId(dto.getExternalId());
        entity.setLastSyncedAt(dto.getLastSyncedAt());
    }
}