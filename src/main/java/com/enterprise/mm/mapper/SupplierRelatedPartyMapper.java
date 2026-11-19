package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.SupplierRelatedPartyResponse;
import com.enterprise.mm.payload.SupplierRelatedPartyUpdateRequest;
import com.enterprise.mm.payload.SupplierRelatedPartyCreateRequest;
import com.enterprise.mm.entity.SupplierRelatedParty;
import org.springframework.stereotype.Component;

@Component
public class SupplierRelatedPartyMapper {
    public SupplierRelatedPartyResponse toResponse(SupplierRelatedParty entity) {
        if (entity == null) {
            return null;
        }
        SupplierRelatedPartyResponse dto = new SupplierRelatedPartyResponse();
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
        dto.setPartyType(entity.getPartyType());
        dto.setFullName(entity.getFullName());
        dto.setLegalName(entity.getLegalName());
        dto.setTaxId(entity.getTaxId());
        dto.setCountryCode(entity.getCountryCode());
        dto.setOwnershipPercent(entity.getOwnershipPercent());
        dto.setPositionTitle(entity.getPositionTitle());
        dto.setPhone(entity.getPhone());
        dto.setEmail(entity.getEmail());
        dto.setPepFlag(entity.isPepFlag());
        dto.setSanctionsMatch(entity.isSanctionsMatch());
        dto.setConflictOfInterestFlag(entity.isConflictOfInterestFlag());
        dto.setRiskLevel(entity.getRiskLevel());
        dto.setValidFrom(entity.getValidFrom());
        dto.setValidTo(entity.getValidTo());
        return dto;
    }

    public SupplierRelatedParty toEntity(SupplierRelatedPartyCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        SupplierRelatedParty entity = new SupplierRelatedParty();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setSupplierId(dto.getSupplierId());
        entity.setPartyType(dto.getPartyType());
        entity.setFullName(dto.getFullName());
        entity.setLegalName(dto.getLegalName());
        entity.setTaxId(dto.getTaxId());
        entity.setCountryCode(dto.getCountryCode());
        entity.setOwnershipPercent(dto.getOwnershipPercent());
        entity.setPositionTitle(dto.getPositionTitle());
        entity.setPhone(dto.getPhone());
        entity.setEmail(dto.getEmail());
        entity.setPepFlag(dto.isPepFlag());
        entity.setSanctionsMatch(dto.isSanctionsMatch());
        entity.setConflictOfInterestFlag(dto.isConflictOfInterestFlag());
        entity.setRiskLevel(dto.getRiskLevel());
        entity.setValidFrom(dto.getValidFrom());
        entity.setValidTo(dto.getValidTo());
        return entity;
    }

    public void updateEntity(SupplierRelatedParty entity, SupplierRelatedPartyUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setPartyType(dto.getPartyType());
        entity.setFullName(dto.getFullName());
        entity.setLegalName(dto.getLegalName());
        entity.setTaxId(dto.getTaxId());
        entity.setCountryCode(dto.getCountryCode());
        entity.setOwnershipPercent(dto.getOwnershipPercent());
        entity.setPositionTitle(dto.getPositionTitle());
        entity.setPhone(dto.getPhone());
        entity.setEmail(dto.getEmail());
        entity.setPepFlag(dto.isPepFlag());
        entity.setSanctionsMatch(dto.isSanctionsMatch());
        entity.setConflictOfInterestFlag(dto.isConflictOfInterestFlag());
        entity.setRiskLevel(dto.getRiskLevel());
        entity.setValidFrom(dto.getValidFrom());
        entity.setValidTo(dto.getValidTo());
        // Supplier ownership and lifecycle status are not editable through the generic update route.
    }
}
