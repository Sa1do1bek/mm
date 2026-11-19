package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.ApprovalStepResponse;
import com.enterprise.mm.payload.ApprovalStepUpdateRequest;
import com.enterprise.mm.payload.ApprovalStepCreateRequest;
import com.enterprise.mm.entity.ApprovalStep;
import org.springframework.stereotype.Component;

@Component
public class ApprovalStepMapper {
    public ApprovalStepResponse toResponse(ApprovalStep entity) {
        if (entity == null) {
            return null;
        }
        ApprovalStepResponse dto = new ApprovalStepResponse();
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
        dto.setApprovalWorkflowId(entity.getApprovalWorkflowId());
        dto.setStepNumber(entity.getStepNumber());
        dto.setApproverRole(entity.getApproverRole());
        dto.setApprovedBy(entity.getApprovedBy());
        dto.setApprovedAt(entity.getApprovedAt());
        dto.setDecision(entity.getDecision());
        dto.setCommentText(entity.getCommentText());
        return dto;
    }

    public ApprovalStep toEntity(ApprovalStepCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        ApprovalStep entity = new ApprovalStep();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setApprovalWorkflowId(dto.getApprovalWorkflowId());
        entity.setStepNumber(dto.getStepNumber());
        entity.setApproverRole(dto.getApproverRole());
        entity.setApprovedBy(dto.getApprovedBy());
        entity.setApprovedAt(dto.getApprovedAt());
        entity.setDecision(dto.getDecision());
        entity.setCommentText(dto.getCommentText());
        return entity;
    }

    public void updateEntity(ApprovalStep entity, ApprovalStepUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setApprovalWorkflowId(dto.getApprovalWorkflowId());
        entity.setStepNumber(dto.getStepNumber());
        entity.setApproverRole(dto.getApproverRole());
        entity.setApprovedBy(dto.getApprovedBy());
        entity.setApprovedAt(dto.getApprovedAt());
        entity.setDecision(dto.getDecision());
        entity.setCommentText(dto.getCommentText());
    }
}