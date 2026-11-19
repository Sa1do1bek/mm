package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.SupplierAccreditationDecision;
import com.enterprise.mm.payload.SupplierAccreditationDecisionCreateRequest;
import com.enterprise.mm.payload.SupplierAccreditationDecisionResponse;
import com.enterprise.mm.payload.SupplierAccreditationDecisionUpdateRequest;
import org.springframework.stereotype.Component;

@Component
public class SupplierAccreditationDecisionMapper {
    public SupplierAccreditationDecisionResponse toResponse(SupplierAccreditationDecision entity) {
        if (entity == null) {
            return null;
        }
        SupplierAccreditationDecisionResponse dto = new SupplierAccreditationDecisionResponse();
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
        dto.setApplicationId(entity.getApplicationId());
        dto.setApplicationCode(BaseMapper.code(entity.getApplication()));
        dto.setApplicationName(BaseMapper.name(entity.getApplication()));
        dto.setDecision(entity.getDecision());
        dto.setApprovedScope(entity.getApprovedScope());
        dto.setAllowedMaterialGroupIds(entity.getAllowedMaterialGroupIds());
        dto.setValidFrom(entity.getValidFrom());
        dto.setValidTo(entity.getValidTo());
        dto.setConditions(entity.getConditions());
        dto.setCommissionProtocolNo(entity.getCommissionProtocolNo());
        return dto;
    }

    public SupplierAccreditationDecision toEntity(SupplierAccreditationDecisionCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        SupplierAccreditationDecision entity = new SupplierAccreditationDecision();
        update(entity, dto);
        return entity;
    }

    public void updateEntity(SupplierAccreditationDecision entity, SupplierAccreditationDecisionUpdateRequest dto) {
        update(entity, dto);
    }

    private void update(SupplierAccreditationDecision entity, SupplierAccreditationDecisionCreateRequest dto) {
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setApplicationId(dto.getApplicationId());
        entity.setDecision(dto.getDecision());
        entity.setApprovedScope(dto.getApprovedScope());
        entity.setAllowedMaterialGroupIds(dto.getAllowedMaterialGroupIds());
        entity.setValidFrom(dto.getValidFrom());
        entity.setValidTo(dto.getValidTo());
        entity.setConditions(dto.getConditions());
        entity.setCommissionProtocolNo(dto.getCommissionProtocolNo());
    }
}
