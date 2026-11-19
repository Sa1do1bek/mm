package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.SegregationOfDutyRuleResponse;
import com.enterprise.mm.payload.SegregationOfDutyRuleUpdateRequest;
import com.enterprise.mm.payload.SegregationOfDutyRuleCreateRequest;
import com.enterprise.mm.entity.SegregationOfDutyRule;
import org.springframework.stereotype.Component;

@Component
public class SegregationOfDutyRuleMapper {
    public SegregationOfDutyRuleResponse toResponse(SegregationOfDutyRule entity) {
        if (entity == null) {
            return null;
        }
        SegregationOfDutyRuleResponse dto = new SegregationOfDutyRuleResponse();
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
        dto.setEntityType(entity.getEntityType());
        dto.setActionCode(entity.getActionCode());
        dto.setRuleType(entity.getRuleType());
        dto.setSubjectField(entity.getSubjectField());
        dto.setSourceActionCode(entity.getSourceActionCode());
        dto.setStatusScope(entity.getStatusScope());
        dto.setAppliesToRoles(entity.getAppliesToRoles());
        dto.setExemptRoles(entity.getExemptRoles());
        dto.setActiveFlag(entity.isActiveFlag());
        dto.setAllowAdminOverride(entity.isAllowAdminOverride());
        dto.setSeverity(entity.getSeverity());
        dto.setMessage(entity.getMessage());
        return dto;
    }

    public SegregationOfDutyRule toEntity(SegregationOfDutyRuleCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        SegregationOfDutyRule entity = new SegregationOfDutyRule();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setEntityType(dto.getEntityType());
        entity.setActionCode(dto.getActionCode());
        entity.setRuleType(dto.getRuleType());
        entity.setSubjectField(dto.getSubjectField());
        entity.setSourceActionCode(dto.getSourceActionCode());
        entity.setStatusScope(dto.getStatusScope());
        entity.setAppliesToRoles(dto.getAppliesToRoles());
        entity.setExemptRoles(dto.getExemptRoles());
        entity.setActiveFlag(dto.isActiveFlag());
        entity.setAllowAdminOverride(dto.isAllowAdminOverride());
        entity.setSeverity(dto.getSeverity());
        entity.setMessage(dto.getMessage());
        return entity;
    }

    public void updateEntity(SegregationOfDutyRule entity, SegregationOfDutyRuleUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setEntityType(dto.getEntityType());
        entity.setActionCode(dto.getActionCode());
        entity.setRuleType(dto.getRuleType());
        entity.setSubjectField(dto.getSubjectField());
        entity.setSourceActionCode(dto.getSourceActionCode());
        entity.setStatusScope(dto.getStatusScope());
        entity.setAppliesToRoles(dto.getAppliesToRoles());
        entity.setExemptRoles(dto.getExemptRoles());
        entity.setActiveFlag(dto.isActiveFlag());
        entity.setAllowAdminOverride(dto.isAllowAdminOverride());
        entity.setSeverity(dto.getSeverity());
        entity.setMessage(dto.getMessage());
    }
}
