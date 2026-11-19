package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.TenderConflictCheckResponse;
import com.enterprise.mm.payload.TenderConflictCheckUpdateRequest;
import com.enterprise.mm.payload.TenderConflictCheckCreateRequest;
import com.enterprise.mm.entity.TenderConflictCheck;
import org.springframework.stereotype.Component;

@Component
public class TenderConflictCheckMapper {
    public TenderConflictCheckResponse toResponse(TenderConflictCheck entity) {
        if (entity == null) {
            return null;
        }
        TenderConflictCheckResponse dto = new TenderConflictCheckResponse();
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
        dto.setRfqId(entity.getRfqId());
        dto.setRfqCode(BaseMapper.code(entity.getRfq()));
        dto.setRfqName(BaseMapper.name(entity.getRfq()));
        dto.setQuotationId(entity.getQuotationId());
        dto.setQuotationCode(BaseMapper.code(entity.getQuotation()));
        dto.setQuotationName(BaseMapper.name(entity.getQuotation()));
        dto.setSupplierId(entity.getSupplierId());
        dto.setSupplierCode(BaseMapper.supplierCode(entity.getSupplier()));
        dto.setSupplierName(BaseMapper.supplierName(entity.getSupplier()));
        dto.setCommissionMemberId(entity.getCommissionMemberId());
        dto.setCommissionMemberCode(BaseMapper.code(entity.getCommissionMember()));
        dto.setCommissionMemberName(BaseMapper.name(entity.getCommissionMember()));
        dto.setRelatedPartyId(entity.getRelatedPartyId());
        dto.setRelatedPartyCode(BaseMapper.code(entity.getRelatedParty()));
        dto.setRelatedPartyName(BaseMapper.name(entity.getRelatedParty()));
        dto.setConflictFound(entity.isConflictFound());
        dto.setConflictType(entity.getConflictType());
        dto.setSeverity(entity.getSeverity());
        dto.setDetails(entity.getDetails());
        dto.setMitigationAction(entity.getMitigationAction());
        dto.setResolved(entity.isResolved());
        dto.setCheckedAt(entity.getCheckedAt());
        return dto;
    }

    public TenderConflictCheck toEntity(TenderConflictCheckCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        TenderConflictCheck entity = new TenderConflictCheck();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setRfqId(dto.getRfqId());
        entity.setQuotationId(dto.getQuotationId());
        entity.setSupplierId(dto.getSupplierId());
        entity.setCommissionMemberId(dto.getCommissionMemberId());
        entity.setRelatedPartyId(dto.getRelatedPartyId());
        entity.setConflictFound(dto.isConflictFound());
        entity.setConflictType(dto.getConflictType());
        entity.setSeverity(dto.getSeverity());
        entity.setDetails(dto.getDetails());
        entity.setMitigationAction(dto.getMitigationAction());
        entity.setResolved(dto.isResolved());
        entity.setCheckedAt(dto.getCheckedAt());
        return entity;
    }

    public void updateEntity(TenderConflictCheck entity, TenderConflictCheckUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setRfqId(dto.getRfqId());
        entity.setQuotationId(dto.getQuotationId());
        entity.setSupplierId(dto.getSupplierId());
        entity.setCommissionMemberId(dto.getCommissionMemberId());
        entity.setRelatedPartyId(dto.getRelatedPartyId());
        entity.setConflictFound(dto.isConflictFound());
        entity.setConflictType(dto.getConflictType());
        entity.setSeverity(dto.getSeverity());
        entity.setDetails(dto.getDetails());
        entity.setMitigationAction(dto.getMitigationAction());
        entity.setResolved(dto.isResolved());
        entity.setCheckedAt(dto.getCheckedAt());
    }
}