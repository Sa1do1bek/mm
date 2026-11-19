package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.SupplierKycCheckResponse;
import com.enterprise.mm.payload.SupplierKycCheckUpdateRequest;
import com.enterprise.mm.payload.SupplierKycCheckCreateRequest;
import com.enterprise.mm.entity.SupplierKycCheck;
import org.springframework.stereotype.Component;

@Component
public class SupplierKycCheckMapper {
    public SupplierKycCheckResponse toResponse(SupplierKycCheck entity) {
        if (entity == null) {
            return null;
        }
        SupplierKycCheckResponse dto = new SupplierKycCheckResponse();
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
        dto.setCheckType(entity.getCheckType());
        dto.setProvider(entity.getProvider());
        dto.setExternalCheckId(entity.getExternalCheckId());
        dto.setRiskLevel(entity.getRiskLevel());
        dto.setScore(entity.getScore());
        dto.setCheckedAt(entity.getCheckedAt());
        dto.setValidUntil(entity.getValidUntil());
        dto.setNextReviewDate(entity.getNextReviewDate());
        dto.setEvidenceAttachmentId(entity.getEvidenceAttachmentId());
        dto.setFindings(entity.getFindings());
        dto.setRemediationRequired(entity.isRemediationRequired());
        return dto;
    }

    public SupplierKycCheck toEntity(SupplierKycCheckCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        SupplierKycCheck entity = new SupplierKycCheck();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setSupplierId(dto.getSupplierId());
        entity.setCheckType(dto.getCheckType());
        entity.setProvider(dto.getProvider());
        entity.setExternalCheckId(dto.getExternalCheckId());
        entity.setRiskLevel(dto.getRiskLevel());
        entity.setScore(dto.getScore());
        entity.setCheckedAt(dto.getCheckedAt());
        entity.setValidUntil(dto.getValidUntil());
        entity.setNextReviewDate(dto.getNextReviewDate());
        entity.setEvidenceAttachmentId(dto.getEvidenceAttachmentId());
        entity.setFindings(dto.getFindings());
        entity.setRemediationRequired(dto.isRemediationRequired());
        return entity;
    }

    public void updateEntity(SupplierKycCheck entity, SupplierKycCheckUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setCheckType(dto.getCheckType());
        entity.setProvider(dto.getProvider());
        entity.setExternalCheckId(dto.getExternalCheckId());
        entity.setRiskLevel(dto.getRiskLevel());
        entity.setScore(dto.getScore());
        entity.setValidUntil(dto.getValidUntil());
        entity.setNextReviewDate(dto.getNextReviewDate());
        entity.setEvidenceAttachmentId(dto.getEvidenceAttachmentId());
        entity.setFindings(dto.getFindings());
        entity.setRemediationRequired(dto.isRemediationRequired());
        // Supplier ownership, decision state and completion time are command-owned.
    }
}
