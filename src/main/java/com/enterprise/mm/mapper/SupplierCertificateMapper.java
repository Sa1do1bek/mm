package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.SupplierCertificateResponse;
import com.enterprise.mm.payload.SupplierCertificateUpdateRequest;
import com.enterprise.mm.payload.SupplierCertificateCreateRequest;
import com.enterprise.mm.entity.SupplierCertificate;
import org.springframework.stereotype.Component;

@Component
public class SupplierCertificateMapper {
    public SupplierCertificateResponse toResponse(SupplierCertificate entity) {
        if (entity == null) {
            return null;
        }
        SupplierCertificateResponse dto = new SupplierCertificateResponse();
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
        dto.setCertificateNumber(entity.getCertificateNumber());
        dto.setIssuer(entity.getIssuer());
        dto.setValidFrom(entity.getValidFrom());
        dto.setValidTo(entity.getValidTo());
        return dto;
    }

    public SupplierCertificate toEntity(SupplierCertificateCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        SupplierCertificate entity = new SupplierCertificate();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setSupplierId(dto.getSupplierId());
        entity.setCertificateNumber(dto.getCertificateNumber());
        entity.setIssuer(dto.getIssuer());
        entity.setValidFrom(dto.getValidFrom());
        entity.setValidTo(dto.getValidTo());
        return entity;
    }

    public void updateEntity(SupplierCertificate entity, SupplierCertificateUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setSupplierId(dto.getSupplierId());
        entity.setCertificateNumber(dto.getCertificateNumber());
        entity.setIssuer(dto.getIssuer());
        entity.setValidFrom(dto.getValidFrom());
        entity.setValidTo(dto.getValidTo());
    }
}