package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.MaterialQualityIssue;
import com.enterprise.mm.payload.MaterialQualityIssueCreateRequest;
import com.enterprise.mm.payload.MaterialQualityIssueResponse;
import com.enterprise.mm.payload.MaterialQualityIssueUpdateRequest;
import org.springframework.stereotype.Component;

@Component
public class MaterialQualityIssueMapper {
    public MaterialQualityIssueResponse toResponse(MaterialQualityIssue entity) {
        if (entity == null) {
            return null;
        }
        MaterialQualityIssueResponse dto = new MaterialQualityIssueResponse();
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
        dto.setMaterialId(entity.getMaterialId());
        dto.setIssueType(entity.getIssueType());
        dto.setSeverity(entity.getSeverity());
        dto.setFieldName(entity.getFieldName());
        dto.setAssignedTo(entity.getAssignedTo());
        dto.setResolutionComment(entity.getResolutionComment());
        dto.setEvidenceReference(entity.getEvidenceReference());
        dto.setAssignedAt(entity.getAssignedAt());
        dto.setStartedAt(entity.getStartedAt());
        dto.setResolvedAt(entity.getResolvedAt());
        return dto;
    }

    public MaterialQualityIssue toEntity(MaterialQualityIssueCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        MaterialQualityIssue entity = new MaterialQualityIssue();
        updateEntity(entity, dto);
        return entity;
    }

    public void updateEntity(MaterialQualityIssue entity, MaterialQualityIssueCreateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setMaterialId(dto.getMaterialId());
        entity.setIssueType(dto.getIssueType());
        entity.setSeverity(dto.getSeverity());
        entity.setFieldName(dto.getFieldName());
    }

    public void updateEntity(MaterialQualityIssue entity, MaterialQualityIssueUpdateRequest dto) {
        updateEntity(entity, (MaterialQualityIssueCreateRequest) dto);
    }
}
