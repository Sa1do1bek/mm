package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.SegregationOfDutyViolationResponse;
import com.enterprise.mm.payload.SegregationOfDutyViolationUpdateRequest;
import com.enterprise.mm.payload.SegregationOfDutyViolationCreateRequest;
import com.enterprise.mm.entity.SegregationOfDutyViolation;
import org.springframework.stereotype.Component;

@Component
public class SegregationOfDutyViolationMapper {
    public SegregationOfDutyViolationResponse toResponse(SegregationOfDutyViolation entity) {
        if (entity == null) {
            return null;
        }
        SegregationOfDutyViolationResponse dto = new SegregationOfDutyViolationResponse();
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
        dto.setRuleId(entity.getRuleId());
        dto.setEntityType(entity.getEntityType());
        dto.setEntityId(entity.getEntityId());
        dto.setActionCode(entity.getActionCode());
        dto.setRuleType(entity.getRuleType());
        dto.setUsername(entity.getUsername());
        dto.setMatchedPrincipal(entity.getMatchedPrincipal());
        dto.setDecision(entity.getDecision());
        dto.setSeverity(entity.getSeverity());
        dto.setOccurredAt(entity.getOccurredAt());
        dto.setMessage(entity.getMessage());
        return dto;
    }

    public SegregationOfDutyViolation toEntity(SegregationOfDutyViolationCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        SegregationOfDutyViolation entity = new SegregationOfDutyViolation();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setRuleId(dto.getRuleId());
        entity.setEntityType(dto.getEntityType());
        entity.setEntityId(dto.getEntityId());
        entity.setActionCode(dto.getActionCode());
        entity.setRuleType(dto.getRuleType());
        entity.setUsername(dto.getUsername());
        entity.setMatchedPrincipal(dto.getMatchedPrincipal());
        entity.setDecision(dto.getDecision());
        entity.setSeverity(dto.getSeverity());
        entity.setOccurredAt(dto.getOccurredAt());
        entity.setMessage(dto.getMessage());
        return entity;
    }

    public void updateEntity(SegregationOfDutyViolation entity, SegregationOfDutyViolationUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setRuleId(dto.getRuleId());
        entity.setEntityType(dto.getEntityType());
        entity.setEntityId(dto.getEntityId());
        entity.setActionCode(dto.getActionCode());
        entity.setRuleType(dto.getRuleType());
        entity.setUsername(dto.getUsername());
        entity.setMatchedPrincipal(dto.getMatchedPrincipal());
        entity.setDecision(dto.getDecision());
        entity.setSeverity(dto.getSeverity());
        entity.setOccurredAt(dto.getOccurredAt());
        entity.setMessage(dto.getMessage());
    }
}