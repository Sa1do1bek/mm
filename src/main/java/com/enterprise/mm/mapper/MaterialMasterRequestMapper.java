package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.MaterialMasterRequest;
import com.enterprise.mm.payload.MaterialMasterRequestCreateRequest;
import com.enterprise.mm.payload.MaterialMasterRequestResponse;
import com.enterprise.mm.payload.MaterialMasterRequestUpdateRequest;
import org.springframework.stereotype.Component;

@Component
public class MaterialMasterRequestMapper {
    public MaterialMasterRequestResponse toResponse(MaterialMasterRequest entity) {
        if (entity == null) {
            return null;
        }
        MaterialMasterRequestResponse dto = new MaterialMasterRequestResponse();
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
        dto.setRequestType(entity.getRequestType());
        dto.setMaterialId(entity.getMaterialId());
        dto.setTargetMaterialId(entity.getTargetMaterialId());
        dto.setProposedName(entity.getProposedName());
        dto.setMaterialGroupId(entity.getMaterialGroupId());
        dto.setUomId(entity.getUomId());
        dto.setMxikCode(entity.getMxikCode());
        dto.setTnvedCode(entity.getTnvedCode());
        dto.setRequestedBy(entity.getRequestedBy());
        dto.setLastActionComment(entity.getLastActionComment());
        dto.setSubmittedAt(entity.getSubmittedAt());
        dto.setReviewedAt(entity.getReviewedAt());
        dto.setDecidedAt(entity.getDecidedAt());
        dto.setAppliedAt(entity.getAppliedAt());
        return dto;
    }

    public MaterialMasterRequest toEntity(MaterialMasterRequestCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        MaterialMasterRequest entity = new MaterialMasterRequest();
        updateEntity(entity, dto);
        return entity;
    }

    public void updateEntity(MaterialMasterRequest entity, MaterialMasterRequestCreateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setRequestType(dto.getRequestType());
        entity.setMaterialId(dto.getMaterialId());
        entity.setTargetMaterialId(dto.getTargetMaterialId());
        entity.setProposedName(dto.getProposedName());
        entity.setMaterialGroupId(dto.getMaterialGroupId());
        entity.setUomId(dto.getUomId());
        entity.setMxikCode(dto.getMxikCode());
        entity.setTnvedCode(dto.getTnvedCode());
        entity.setRequestedBy(dto.getRequestedBy());
    }

    public void updateEntity(MaterialMasterRequest entity, MaterialMasterRequestUpdateRequest dto) {
        updateEntity(entity, (MaterialMasterRequestCreateRequest) dto);
    }
}
