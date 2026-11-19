package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.MaterialDuplicateCandidate;
import com.enterprise.mm.payload.MaterialDuplicateCandidateCreateRequest;
import com.enterprise.mm.payload.MaterialDuplicateCandidateResponse;
import com.enterprise.mm.payload.MaterialDuplicateCandidateUpdateRequest;
import org.springframework.stereotype.Component;

@Component
public class MaterialDuplicateCandidateMapper {
    public MaterialDuplicateCandidateResponse toResponse(MaterialDuplicateCandidate entity) {
        if (entity == null) {
            return null;
        }
        MaterialDuplicateCandidateResponse dto = new MaterialDuplicateCandidateResponse();
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
        dto.setCandidateMaterialId(entity.getCandidateMaterialId());
        dto.setSimilarityScore(entity.getSimilarityScore());
        dto.setReason(entity.getReason());
        dto.setAiSourceJobId(entity.getAiSourceJobId());
        dto.setAiEvidenceJson(entity.getAiEvidenceJson());
        dto.setDecisionComment(entity.getDecisionComment());
        dto.setDecidedAt(entity.getDecidedAt());
        dto.setMergedAt(entity.getMergedAt());
        return dto;
    }

    public MaterialDuplicateCandidate toEntity(MaterialDuplicateCandidateCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        MaterialDuplicateCandidate entity = new MaterialDuplicateCandidate();
        updateEntity(entity, dto);
        return entity;
    }

    public void updateEntity(MaterialDuplicateCandidate entity, MaterialDuplicateCandidateCreateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setMaterialId(dto.getMaterialId());
        entity.setCandidateMaterialId(dto.getCandidateMaterialId());
        entity.setSimilarityScore(dto.getSimilarityScore());
        entity.setReason(dto.getReason());
        entity.setAiSourceJobId(dto.getAiSourceJobId());
        entity.setAiEvidenceJson(dto.getAiEvidenceJson());
    }

    public void updateEntity(MaterialDuplicateCandidate entity, MaterialDuplicateCandidateUpdateRequest dto) {
        updateEntity(entity, (MaterialDuplicateCandidateCreateRequest) dto);
    }
}
