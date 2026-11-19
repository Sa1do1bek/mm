package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.TenderCommissionMemberResponse;
import com.enterprise.mm.payload.TenderCommissionMemberUpdateRequest;
import com.enterprise.mm.payload.TenderCommissionMemberCreateRequest;
import com.enterprise.mm.entity.TenderCommissionMember;
import org.springframework.stereotype.Component;

@Component
public class TenderCommissionMemberMapper {
    public TenderCommissionMemberResponse toResponse(TenderCommissionMember entity) {
        if (entity == null) {
            return null;
        }
        TenderCommissionMemberResponse dto = new TenderCommissionMemberResponse();
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
        dto.setEmployeeId(entity.getEmployeeId());
        dto.setEmployeeCode(BaseMapper.employeeCode(entity.getEmployee()));
        dto.setEmployeeName(BaseMapper.employeeName(entity.getEmployee()));
        dto.setMemberName(entity.getMemberName());
        dto.setMemberRole(entity.getMemberRole());
        dto.setVotingWeight(entity.getVotingWeight());
        dto.setConflictDeclarationSigned(entity.isConflictDeclarationSigned());
        dto.setDeclaredAt(entity.getDeclaredAt());
        dto.setActive(entity.isActive());
        return dto;
    }

    public TenderCommissionMember toEntity(TenderCommissionMemberCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        TenderCommissionMember entity = new TenderCommissionMember();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setRfqId(dto.getRfqId());
        entity.setEmployeeId(dto.getEmployeeId());
        entity.setMemberName(dto.getMemberName());
        if (dto.getMemberRole() != null) {
            entity.setMemberRole(dto.getMemberRole());
        }
        if (dto.getVotingWeight() != null) {
            entity.setVotingWeight(dto.getVotingWeight());
        }
        if (dto.getConflictDeclarationSigned() != null) {
            entity.setConflictDeclarationSigned(dto.getConflictDeclarationSigned());
        }
        entity.setDeclaredAt(dto.getDeclaredAt());
        if (dto.getActive() != null) {
            entity.setActive(dto.getActive());
        }
        return entity;
    }

    public void updateEntity(TenderCommissionMember entity, TenderCommissionMemberUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setRfqId(dto.getRfqId());
        entity.setEmployeeId(dto.getEmployeeId());
        entity.setMemberName(dto.getMemberName());
        if (dto.getMemberRole() != null) {
            entity.setMemberRole(dto.getMemberRole());
        }
        if (dto.getVotingWeight() != null) {
            entity.setVotingWeight(dto.getVotingWeight());
        }
        if (dto.getConflictDeclarationSigned() != null) {
            entity.setConflictDeclarationSigned(dto.getConflictDeclarationSigned());
        }
        entity.setDeclaredAt(dto.getDeclaredAt());
        if (dto.getActive() != null) {
            entity.setActive(dto.getActive());
        }
    }
}
