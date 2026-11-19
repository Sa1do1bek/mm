package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.ApprovalWorkflowResponse;
import com.enterprise.mm.payload.ApprovalWorkflowUpdateRequest;
import com.enterprise.mm.payload.ApprovalWorkflowCreateRequest;
import com.enterprise.mm.entity.ApprovalWorkflow;
import org.springframework.stereotype.Component;

@Component
public class ApprovalWorkflowMapper {
    public ApprovalWorkflowResponse toResponse(ApprovalWorkflow entity) {
        if (entity == null) {
            return null;
        }
        ApprovalWorkflowResponse dto = new ApprovalWorkflowResponse();
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
        dto.setModuleKey(entity.getModuleKey());
        dto.setRequestType(entity.getRequestType());
        dto.setEntityType(entity.getEntityType());
        dto.setEntityId(entity.getEntityId());
        dto.setCurrentStep(entity.getCurrentStep());
        return dto;
    }

    public ApprovalWorkflow toEntity(ApprovalWorkflowCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        ApprovalWorkflow entity = new ApprovalWorkflow();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setModuleKey(dto.getModuleKey());
        entity.setRequestType(dto.getRequestType());
        entity.setEntityType(dto.getEntityType());
        entity.setEntityId(dto.getEntityId());
        entity.setCurrentStep(dto.getCurrentStep());
        return entity;
    }

    public void updateEntity(ApprovalWorkflow entity, ApprovalWorkflowUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setModuleKey(dto.getModuleKey());
        entity.setRequestType(dto.getRequestType());
        entity.setEntityType(dto.getEntityType());
        entity.setEntityId(dto.getEntityId());
        entity.setCurrentStep(dto.getCurrentStep());
    }
}
