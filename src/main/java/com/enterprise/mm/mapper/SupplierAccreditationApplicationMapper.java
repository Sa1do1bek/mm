package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.payload.SupplierAccreditationApplicationCreateRequest;
import com.enterprise.mm.payload.SupplierAccreditationApplicationResponse;
import com.enterprise.mm.payload.SupplierAccreditationApplicationUpdateRequest;
import org.springframework.stereotype.Component;

@Component
public class SupplierAccreditationApplicationMapper {
    public SupplierAccreditationApplicationResponse toResponse(SupplierAccreditationApplication entity) {
        if (entity == null) {
            return null;
        }
        SupplierAccreditationApplicationResponse dto = new SupplierAccreditationApplicationResponse();
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
        dto.setAccreditationType(entity.getAccreditationType());
        dto.setRequestedScope(entity.getRequestedScope());
        dto.setRequestedMaterialGroupIds(entity.getRequestedMaterialGroupIds());
        dto.setSubmittedAt(entity.getSubmittedAt());
        dto.setReviewStartedAt(entity.getReviewStartedAt());
        dto.setValidFrom(entity.getValidFrom());
        dto.setValidTo(entity.getValidTo());
        dto.setRiskScore(entity.getRiskScore());
        dto.setReviewComment(entity.getReviewComment());
        dto.setComplianceRecommendation(entity.getComplianceRecommendation());
        dto.setComplianceSnapshotJson(entity.getComplianceSnapshotJson());
        dto.setParentApplicationId(entity.getParentApplicationId());
        dto.setParentApplicationCode(BaseMapper.code(entity.getParentApplication()));
        dto.setParentApplicationName(BaseMapper.name(entity.getParentApplication()));
        return dto;
    }

    public SupplierAccreditationApplication toEntity(SupplierAccreditationApplicationCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        SupplierAccreditationApplication entity = new SupplierAccreditationApplication();
        update(entity, dto);
        return entity;
    }

    public void updateEntity(SupplierAccreditationApplication entity, SupplierAccreditationApplicationUpdateRequest dto) {
        update(entity, dto);
    }

    private void update(SupplierAccreditationApplication entity, SupplierAccreditationApplicationCreateRequest dto) {
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setSupplierId(dto.getSupplierId());
        entity.setAccreditationType(dto.getAccreditationType());
        entity.setRequestedScope(dto.getRequestedScope());
        entity.setRequestedMaterialGroupIds(dto.getRequestedMaterialGroupIds());
        entity.setValidFrom(dto.getValidFrom());
        entity.setValidTo(dto.getValidTo());
    }
}
