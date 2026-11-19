package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.IntegrationEventResponse;
import com.enterprise.mm.payload.IntegrationEventUpdateRequest;
import com.enterprise.mm.payload.IntegrationEventCreateRequest;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.BusinessDocumentReference;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.enums.IntegrationStatus;
import com.enterprise.mm.service.IntegrationBusinessDocumentResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class IntegrationEventMapper {
    private final IntegrationBusinessDocumentResolver documentResolver;

    public IntegrationEventResponse toResponse(IntegrationEvent entity) {
        if (entity == null) {
            return null;
        }
        IntegrationEventResponse dto = new IntegrationEventResponse();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setCompanyId(entity.getCompanyId());
        dto.setCode(entity.getCode());
        dto.setName(entity.getName());
        dto.setStatus(IntegrationStatus.fromLegacy(entity.getStatus()).name());
        dto.setDescription(entity.getDescription());
        dto.setDeleted(entity.isDeleted());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setUpdatedBy(entity.getUpdatedBy());
        dto.setVersion(entity.getVersion());
        dto.setDirection(entity.getDirection());
        dto.setEventType(entity.getEventType());
        dto.setAggregateType(entity.getAggregateType());
        dto.setAggregateId(entity.getAggregateId());
        dto.setPayload(entity.getPayload());
        dto.setRetryCount(entity.getRetryCount());
        dto.setNextRetryAt(entity.getNextRetryAt());
        dto.setLastAttemptAt(entity.getLastAttemptAt());
        dto.setIdempotencyKey(entity.getIdempotencyKey());
        dto.setLastError(entity.getLastError());
        dto.setExternalId(entity.getExternalId());
        dto.setReplayOfEventId(entity.getReplayOfEventId());
        dto.setManualReason(entity.getManualReason());
        BusinessDocumentReference document = documentResolver.resolve(entity);
        dto.setBusinessDocumentType(document.type());
        dto.setBusinessDocumentId(document.id());
        dto.setBusinessDocumentNumber(document.documentNumber());
        dto.setBusinessDocumentName(document.name());
        dto.setBusinessDocumentModuleKey(document.moduleKey());
        dto.setBusinessDocumentRoute(document.route());
        dto.setBusinessDocumentIdentityAvailable(document.identityAvailable());
        return dto;
    }

    public IntegrationEvent toEntity(IntegrationEventCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        IntegrationEvent entity = new IntegrationEvent();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setEventType(dto.getEventType());
        entity.setAggregateType(dto.getAggregateType());
        entity.setAggregateId(dto.getAggregateId());
        entity.setPayload(dto.getPayload());
        entity.setRetryCount(dto.getRetryCount());
        entity.setNextRetryAt(dto.getNextRetryAt());
        entity.setIdempotencyKey(dto.getIdempotencyKey());
        entity.setLastError(dto.getLastError());
        entity.setExternalId(dto.getExternalId());
        return entity;
    }

    public void updateEntity(IntegrationEvent entity, IntegrationEventUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setEventType(dto.getEventType());
        entity.setAggregateType(dto.getAggregateType());
        entity.setAggregateId(dto.getAggregateId());
        entity.setPayload(dto.getPayload());
        entity.setRetryCount(dto.getRetryCount());
        entity.setNextRetryAt(dto.getNextRetryAt());
        entity.setIdempotencyKey(dto.getIdempotencyKey());
        entity.setLastError(dto.getLastError());
        entity.setExternalId(dto.getExternalId());
    }
}
