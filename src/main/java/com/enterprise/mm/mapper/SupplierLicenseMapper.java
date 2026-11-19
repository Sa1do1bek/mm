package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.SupplierLicenseResponse;
import com.enterprise.mm.payload.SupplierLicenseUpdateRequest;
import com.enterprise.mm.payload.SupplierLicenseCreateRequest;
import com.enterprise.mm.entity.SupplierLicense;
import org.springframework.stereotype.Component;

@Component
public class SupplierLicenseMapper {
    public SupplierLicenseResponse toResponse(SupplierLicense entity) {
        if (entity == null) {
            return null;
        }
        SupplierLicenseResponse dto = new SupplierLicenseResponse();
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
        dto.setLicenseNumber(entity.getLicenseNumber());
        dto.setLicenseType(entity.getLicenseType());
        dto.setIssuingAuthority(entity.getIssuingAuthority());
        dto.setIssueDate(entity.getIssueDate());
        dto.setValidFrom(entity.getValidFrom());
        dto.setValidTo(entity.getValidTo());
        dto.setScope(entity.getScope());
        dto.setTerritory(entity.getTerritory());
        dto.setRenewalRequired(entity.isRenewalRequired());
        dto.setRevokedAt(entity.getRevokedAt());
        dto.setRevocationReason(entity.getRevocationReason());
        return dto;
    }

    public SupplierLicense toEntity(SupplierLicenseCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        SupplierLicense entity = new SupplierLicense();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setSupplierId(dto.getSupplierId());
        entity.setLicenseNumber(dto.getLicenseNumber());
        entity.setLicenseType(dto.getLicenseType());
        entity.setIssuingAuthority(dto.getIssuingAuthority());
        entity.setIssueDate(dto.getIssueDate());
        entity.setValidFrom(dto.getValidFrom());
        entity.setValidTo(dto.getValidTo());
        entity.setScope(dto.getScope());
        entity.setTerritory(dto.getTerritory());
        return entity;
    }

    public void updateEntity(SupplierLicense entity, SupplierLicenseUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setSupplierId(dto.getSupplierId());
        entity.setLicenseNumber(dto.getLicenseNumber());
        entity.setLicenseType(dto.getLicenseType());
        entity.setIssuingAuthority(dto.getIssuingAuthority());
        entity.setIssueDate(dto.getIssueDate());
        entity.setValidFrom(dto.getValidFrom());
        entity.setValidTo(dto.getValidTo());
        entity.setScope(dto.getScope());
        entity.setTerritory(dto.getTerritory());
    }
}
