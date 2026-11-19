package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.IncotermResponse;
import com.enterprise.mm.payload.IncotermUpdateRequest;
import com.enterprise.mm.payload.IncotermCreateRequest;
import com.enterprise.mm.entity.Incoterm;
import org.springframework.stereotype.Component;

@Component
public class IncotermMapper {
    public IncotermResponse toResponse(Incoterm entity) {
        if (entity == null) {
            return null;
        }
        IncotermResponse dto = new IncotermResponse();
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
        dto.setVersionYear(entity.getVersionYear());
        dto.setModeScope(entity.getModeScope());
        dto.setRiskTransferPoint(entity.getRiskTransferPoint());
        dto.setSellerCostResponsibility(entity.getSellerCostResponsibility());
        dto.setBuyerCostResponsibility(entity.getBuyerCostResponsibility());
        dto.setNamedPlaceRequired(entity.isNamedPlaceRequired());
        return dto;
    }

    public Incoterm toEntity(IncotermCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        Incoterm entity = new Incoterm();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setVersionYear(dto.getVersionYear());
        entity.setModeScope(dto.getModeScope());
        entity.setRiskTransferPoint(dto.getRiskTransferPoint());
        entity.setSellerCostResponsibility(dto.getSellerCostResponsibility());
        entity.setBuyerCostResponsibility(dto.getBuyerCostResponsibility());
        entity.setNamedPlaceRequired(dto.isNamedPlaceRequired());
        return entity;
    }

    public void updateEntity(Incoterm entity, IncotermUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setVersionYear(dto.getVersionYear());
        entity.setModeScope(dto.getModeScope());
        entity.setRiskTransferPoint(dto.getRiskTransferPoint());
        entity.setSellerCostResponsibility(dto.getSellerCostResponsibility());
        entity.setBuyerCostResponsibility(dto.getBuyerCostResponsibility());
        entity.setNamedPlaceRequired(dto.isNamedPlaceRequired());
    }
}