package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.AuditLogResponse;
import com.enterprise.mm.payload.AuditLogUpdateRequest;
import com.enterprise.mm.payload.AuditLogCreateRequest;
import com.enterprise.mm.entity.AuditLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuditLogMapper {
    private final ObjectMapper objectMapper;

    public AuditLogResponse toResponse(AuditLog entity) {
        if (entity == null) {
            return null;
        }
        AuditLogResponse dto = new AuditLogResponse();
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
        dto.setEntityId(entity.getEntityId());
        dto.setAction(entity.getAction());
        dto.setFullName(entity.getFullName());
        dto.setDetails(entity.getDetails());
        dto.setOldSnapshot(readJson(entity.getOldSnapshot()));
        dto.setNewSnapshot(readJson(entity.getNewSnapshot()));
        dto.setDiff(readJson(entity.getDiff()));
        dto.setOccurredAt(entity.getOccurredAt());
        dto.setIpAddress(entity.getIpAddress());
        return dto;
    }

    public AuditLog toEntity(AuditLogCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        AuditLog entity = new AuditLog();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setEntityType(dto.getEntityType());
        entity.setEntityId(dto.getEntityId());
        entity.setAction(dto.getAction());
        entity.setName(dto.getUsername());
        entity.setFullName(dto.getFullName());
        entity.setDetails(dto.getDetails());
        entity.setOldSnapshot(dto.getOldSnapshot());
        entity.setNewSnapshot(dto.getNewSnapshot());
        entity.setDiff(dto.getDiff());
        entity.setOccurredAt(dto.getOccurredAt());
        entity.setIpAddress(dto.getIpAddress());
        return entity;
    }

    public void updateEntity(AuditLog entity, AuditLogUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setEntityType(dto.getEntityType());
        entity.setEntityId(dto.getEntityId());
        entity.setAction(dto.getAction());
        entity.setName(dto.getUsername());
        entity.setFullName(dto.getFullName());
        entity.setDetails(dto.getDetails());
        entity.setOldSnapshot(dto.getOldSnapshot());
        entity.setNewSnapshot(dto.getNewSnapshot());
        entity.setDiff(dto.getDiff());
        entity.setOccurredAt(dto.getOccurredAt());
        entity.setIpAddress(dto.getIpAddress());
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception ignored) {
            return null;
        }
    }
}
