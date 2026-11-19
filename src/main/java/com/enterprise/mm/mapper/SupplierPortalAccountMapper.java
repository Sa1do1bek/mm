package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.SupplierPortalAccountResponse;
import com.enterprise.mm.payload.SupplierPortalAccountUpdateRequest;
import com.enterprise.mm.payload.SupplierPortalAccountCreateRequest;
import com.enterprise.mm.entity.SupplierPortalAccount;
import org.springframework.stereotype.Component;

@Component
public class SupplierPortalAccountMapper {
    public SupplierPortalAccountResponse toResponse(SupplierPortalAccount entity) {
        if (entity == null) {
            return null;
        }
        SupplierPortalAccountResponse dto = new SupplierPortalAccountResponse();
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
        dto.setLoginEmail(entity.getLoginEmail());
        dto.setContactPerson(entity.getContactPerson());
        dto.setPortalRole(entity.getPortalRole());
        dto.setPhone(entity.getPhone());
        dto.setInviteTokenHash(null);
        dto.setInviteTokenExpiresAt(entity.getInviteTokenExpiresAt());
        dto.setInvitedAt(entity.getInvitedAt());
        dto.setActivatedAt(entity.getActivatedAt());
        dto.setLastLoginAt(entity.getLastLoginAt());
        dto.setLastSubmissionAt(entity.getLastSubmissionAt());
        dto.setAllowedIpRange(entity.getAllowedIpRange());
        dto.setMfaRequired(entity.isMfaRequired());
        dto.setTermsAcceptedAt(entity.getTermsAcceptedAt());
        return dto;
    }

    public SupplierPortalAccount toEntity(SupplierPortalAccountCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        SupplierPortalAccount entity = new SupplierPortalAccount();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setSupplierId(dto.getSupplierId());
        entity.setLoginEmail(dto.getLoginEmail());
        entity.setContactPerson(dto.getContactPerson());
        entity.setPortalRole(dto.getPortalRole());
        entity.setPhone(dto.getPhone());
        return entity;
    }

    public void updateEntity(SupplierPortalAccount entity, SupplierPortalAccountUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setLoginEmail(dto.getLoginEmail());
        entity.setContactPerson(dto.getContactPerson());
        entity.setPortalRole(dto.getPortalRole());
        entity.setPhone(dto.getPhone());
        // Supplier ownership, lifecycle state, security controls and system timestamps are command-owned.
    }
}
