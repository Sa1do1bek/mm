package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.NotificationResponse;
import com.enterprise.mm.payload.NotificationUpdateRequest;
import com.enterprise.mm.payload.NotificationCreateRequest;
import com.enterprise.mm.entity.Notification;
import org.springframework.stereotype.Component;

@Component
public class NotificationMapper {
    public NotificationResponse toResponse(Notification entity) {
        if (entity == null) {
            return null;
        }
        NotificationResponse dto = new NotificationResponse();
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
        dto.setRecipientUsername(entity.getRecipientUsername());
        dto.setTitle(entity.getTitle());
        dto.setMessage(entity.getMessage());
        dto.setTitleKey(entity.getTitleKey());
        dto.setMessageKey(entity.getMessageKey());
        dto.setLanguage(entity.getRenderedLanguage());
        dto.setEntityType(entity.getEntityType());
        dto.setEntityId(entity.getEntityId());
        dto.setRead(entity.isRead());
        return dto;
    }

    public Notification toEntity(NotificationCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        Notification entity = new Notification();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setRecipientUsername(dto.getRecipientUsername());
        entity.setTitle(dto.getTitle());
        entity.setMessage(dto.getMessage());
        entity.setTitleKey(dto.getTitleKey());
        entity.setMessageKey(dto.getMessageKey());
        entity.setEntityType(dto.getEntityType());
        entity.setEntityId(dto.getEntityId());
        entity.setRead(dto.isRead());
        return entity;
    }

    public void updateEntity(Notification entity, NotificationUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setRecipientUsername(dto.getRecipientUsername());
        entity.setTitle(dto.getTitle());
        entity.setMessage(dto.getMessage());
        entity.setTitleKey(dto.getTitleKey());
        entity.setMessageKey(dto.getMessageKey());
        entity.setEntityType(dto.getEntityType());
        entity.setEntityId(dto.getEntityId());
        entity.setRead(dto.isRead());
    }
}
