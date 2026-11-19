package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.SupplierPortalSubmissionResponse;
import com.enterprise.mm.payload.SupplierPortalSubmissionUpdateRequest;
import com.enterprise.mm.payload.SupplierPortalSubmissionCreateRequest;
import com.enterprise.mm.entity.SupplierPortalSubmission;
import org.springframework.stereotype.Component;

@Component
public class SupplierPortalSubmissionMapper {
    public SupplierPortalSubmissionResponse toResponse(SupplierPortalSubmission entity) {
        if (entity == null) {
            return null;
        }
        SupplierPortalSubmissionResponse dto = new SupplierPortalSubmissionResponse();
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
        dto.setSupplierId(entity.getSupplierId());
        dto.setSupplierCode(BaseMapper.supplierCode(entity.getSupplier()));
        dto.setSupplierName(BaseMapper.supplierName(entity.getSupplier()));
        dto.setPortalAccountId(entity.getPortalAccountId());
        dto.setPortalAccountCode(BaseMapper.code(entity.getPortalAccount()));
        dto.setPortalAccountName(BaseMapper.name(entity.getPortalAccount()));
        dto.setSubmissionType(entity.getSubmissionType());
        dto.setReferenceEntityType(entity.getReferenceEntityType());
        dto.setReferenceId(entity.getReferenceId());
        dto.setPayloadJson(entity.getPayloadJson());
        dto.setAttachmentId(entity.getAttachmentId());
        dto.setSubmittedAt(entity.getSubmittedAt());
        dto.setReviewedAt(entity.getReviewedAt());
        dto.setReviewedBy(entity.getReviewedBy());
        dto.setReviewComment(entity.getReviewComment());
        return dto;
    }

    public SupplierPortalSubmission toEntity(SupplierPortalSubmissionCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        SupplierPortalSubmission entity = new SupplierPortalSubmission();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setSupplierId(dto.getSupplierId());
        entity.setPortalAccountId(dto.getPortalAccountId());
        entity.setSubmissionType(dto.getSubmissionType());
        entity.setReferenceEntityType(dto.getReferenceEntityType());
        entity.setReferenceId(dto.getReferenceId());
        entity.setPayloadJson(dto.getPayloadJson());
        entity.setAttachmentId(dto.getAttachmentId());
        return entity;
    }

    public void updateEntity(SupplierPortalSubmission entity, SupplierPortalSubmissionUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setSubmissionType(dto.getSubmissionType());
        entity.setPayloadJson(dto.getPayloadJson());
        entity.setAttachmentId(dto.getAttachmentId());
        // Ownership, provenance and workflow review metadata are command-owned.
    }
}
