package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.ContractAddendumResponse;
import com.enterprise.mm.payload.ContractAddendumUpdateRequest;
import com.enterprise.mm.payload.ContractAddendumCreateRequest;
import com.enterprise.mm.entity.ContractAddendum;
import org.springframework.stereotype.Component;

@Component
public class ContractAddendumMapper {
    public ContractAddendumResponse toResponse(ContractAddendum entity) {
        if (entity == null) {
            return null;
        }
        ContractAddendumResponse dto = new ContractAddendumResponse();
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
        dto.setContractId(entity.getContractId());
        dto.setContractCode(BaseMapper.code(entity.getContract()));
        dto.setContractName(BaseMapper.name(entity.getContract()));
        dto.setAddendumNumber(entity.getAddendumNumber());
        dto.setAddendumDate(entity.getAddendumDate());
        dto.setAddendumType(entity.getAddendumType());
        dto.setEffectiveDate(entity.getEffectiveDate());
        dto.setNewEndDate(entity.getNewEndDate());
        dto.setLimitDelta(entity.getLimitDelta());
        dto.setNewLimitAmount(entity.getNewLimitAmount());
        dto.setReason(entity.getReason());
        dto.setPostedAt(entity.getPostedAt());
        return dto;
    }

    public ContractAddendum toEntity(ContractAddendumCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        ContractAddendum entity = new ContractAddendum();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setContractId(dto.getContractId());
        entity.setAddendumNumber(dto.getAddendumNumber());
        entity.setAddendumDate(dto.getAddendumDate());
        entity.setAddendumType(dto.getAddendumType());
        entity.setEffectiveDate(dto.getEffectiveDate());
        entity.setNewEndDate(dto.getNewEndDate());
        entity.setLimitDelta(dto.getLimitDelta());
        entity.setNewLimitAmount(dto.getNewLimitAmount());
        entity.setReason(dto.getReason());
        entity.setPostedAt(dto.getPostedAt());
        return entity;
    }

    public void updateEntity(ContractAddendum entity, ContractAddendumUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setContractId(dto.getContractId());
        entity.setAddendumNumber(dto.getAddendumNumber());
        entity.setAddendumDate(dto.getAddendumDate());
        entity.setAddendumType(dto.getAddendumType());
        entity.setEffectiveDate(dto.getEffectiveDate());
        entity.setNewEndDate(dto.getNewEndDate());
        entity.setLimitDelta(dto.getLimitDelta());
        entity.setNewLimitAmount(dto.getNewLimitAmount());
        entity.setReason(dto.getReason());
        entity.setPostedAt(dto.getPostedAt());
    }
}