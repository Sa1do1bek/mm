package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.SupplierAccreditationDocument;
import com.enterprise.mm.payload.SupplierAccreditationDocumentCreateRequest;
import com.enterprise.mm.payload.SupplierAccreditationDocumentResponse;
import com.enterprise.mm.payload.SupplierAccreditationDocumentUpdateRequest;
import org.springframework.stereotype.Component;

@Component
public class SupplierAccreditationDocumentMapper {
    public SupplierAccreditationDocumentResponse toResponse(SupplierAccreditationDocument entity) {
        if (entity == null) {
            return null;
        }
        SupplierAccreditationDocumentResponse dto = new SupplierAccreditationDocumentResponse();
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
        dto.setApplicationId(entity.getApplicationId());
        dto.setApplicationCode(BaseMapper.code(entity.getApplication()));
        dto.setApplicationName(BaseMapper.name(entity.getApplication()));
        dto.setDocumentType(entity.getDocumentType());
        dto.setAttachmentId(entity.getAttachmentId());
        dto.setValidFrom(entity.getValidFrom());
        dto.setValidTo(entity.getValidTo());
        dto.setVerifiedBy(entity.getVerifiedBy());
        dto.setVerifiedAt(entity.getVerifiedAt());
        return dto;
    }

    public SupplierAccreditationDocument toEntity(SupplierAccreditationDocumentCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        SupplierAccreditationDocument entity = new SupplierAccreditationDocument();
        update(entity, dto);
        return entity;
    }

    public void updateEntity(SupplierAccreditationDocument entity, SupplierAccreditationDocumentUpdateRequest dto) {
        update(entity, dto);
    }

    private void update(SupplierAccreditationDocument entity, SupplierAccreditationDocumentCreateRequest dto) {
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setApplicationId(dto.getApplicationId());
        entity.setDocumentType(dto.getDocumentType());
        entity.setAttachmentId(dto.getAttachmentId());
        entity.setValidFrom(dto.getValidFrom());
        entity.setValidTo(dto.getValidTo());
        entity.setVerifiedBy(dto.getVerifiedBy());
        entity.setVerifiedAt(dto.getVerifiedAt());
    }
}
