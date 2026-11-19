package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.TsdOfflineOperationResponse;
import com.enterprise.mm.payload.TsdOfflineOperationUpdateRequest;
import com.enterprise.mm.payload.TsdOfflineOperationCreateRequest;
import com.enterprise.mm.entity.TsdOfflineOperation;
import org.springframework.stereotype.Component;

@Component
public class TsdOfflineOperationMapper {
    public TsdOfflineOperationResponse toResponse(TsdOfflineOperation entity) {
        if (entity == null) {
            return null;
        }
        TsdOfflineOperationResponse dto = new TsdOfflineOperationResponse();
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
        dto.setDeviceId(entity.getDeviceId());
        dto.setClientOperationId(entity.getClientOperationId());
        dto.setOperationType(entity.getOperationType());
        dto.setPayloadJson(entity.getPayloadJson());
        dto.setServerOperationId(entity.getServerOperationId());
        dto.setStockMovementId(entity.getStockMovementId());
        dto.setAttemptCount(entity.getAttemptCount());
        dto.setLastError(entity.getLastError());
        dto.setCreatedOfflineAt(entity.getCreatedOfflineAt());
        dto.setSyncedAt(entity.getSyncedAt());
        return dto;
    }

    public TsdOfflineOperation toEntity(TsdOfflineOperationCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        TsdOfflineOperation entity = new TsdOfflineOperation();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setDeviceId(dto.getDeviceId());
        entity.setClientOperationId(dto.getClientOperationId());
        entity.setOperationType(dto.getOperationType());
        entity.setPayloadJson(dto.getPayloadJson());
        entity.setServerOperationId(dto.getServerOperationId());
        entity.setStockMovementId(dto.getStockMovementId());
        entity.setAttemptCount(dto.getAttemptCount());
        entity.setLastError(dto.getLastError());
        entity.setCreatedOfflineAt(dto.getCreatedOfflineAt());
        entity.setSyncedAt(dto.getSyncedAt());
        return entity;
    }

    public void updateEntity(TsdOfflineOperation entity, TsdOfflineOperationUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setDeviceId(dto.getDeviceId());
        entity.setClientOperationId(dto.getClientOperationId());
        entity.setOperationType(dto.getOperationType());
        entity.setPayloadJson(dto.getPayloadJson());
        entity.setServerOperationId(dto.getServerOperationId());
        entity.setStockMovementId(dto.getStockMovementId());
        entity.setAttemptCount(dto.getAttemptCount());
        entity.setLastError(dto.getLastError());
        entity.setCreatedOfflineAt(dto.getCreatedOfflineAt());
        entity.setSyncedAt(dto.getSyncedAt());
    }
}