package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.AttachmentDto;
import com.enterprise.mm.entity.Attachment;
import org.springframework.stereotype.Component;

@Component
public class AttachmentMapper {
    public AttachmentDto toDto(Attachment entity) {
        if (entity == null) {
            return null;
        }
        AttachmentDto dto = new AttachmentDto();
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
        dto.setAttachmentType(entity.getAttachmentType());
        dto.setOriginalFileName(entity.getOriginalFileName());
        dto.setContentType(entity.getContentType());
        dto.setSizeBytes(entity.getSizeBytes());
        dto.setChecksumSha256(entity.getChecksumSha256());
        dto.setStorageProvider(entity.getStorageProvider());
        dto.setUploadedAt(entity.getUploadedAt());
        dto.setDownloadable(entity.isDownloadable());
        dto.setPreviewable(entity.isPreviewable());
        return dto;
    }

    public Attachment toEntity(AttachmentDto dto) {
        if (dto == null) {
            return null;
        }
        Attachment entity = new Attachment();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setEntityType(dto.getEntityType());
        entity.setEntityId(dto.getEntityId());
        entity.setAttachmentType(dto.getAttachmentType());
        entity.setOriginalFileName(dto.getOriginalFileName());
        entity.setContentType(dto.getContentType());
        entity.setSizeBytes(dto.getSizeBytes());
        entity.setChecksumSha256(dto.getChecksumSha256());
        entity.setStorageProvider(dto.getStorageProvider());
        entity.setUploadedAt(dto.getUploadedAt());
        entity.setDownloadable(dto.isDownloadable());
        entity.setPreviewable(dto.isPreviewable());
        return entity;
    }

    public void updateEntity(Attachment entity, AttachmentDto dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setEntityType(dto.getEntityType());
        entity.setEntityId(dto.getEntityId());
        entity.setAttachmentType(dto.getAttachmentType());
        entity.setOriginalFileName(dto.getOriginalFileName());
        entity.setContentType(dto.getContentType());
        entity.setSizeBytes(dto.getSizeBytes());
        entity.setChecksumSha256(dto.getChecksumSha256());
        entity.setStorageProvider(dto.getStorageProvider());
        entity.setUploadedAt(dto.getUploadedAt());
        entity.setDownloadable(dto.isDownloadable());
        entity.setPreviewable(dto.isPreviewable());
    }
}