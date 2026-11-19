package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.ContractPowerOfAttorneyResponse;
import com.enterprise.mm.payload.ContractPowerOfAttorneyUpdateRequest;
import com.enterprise.mm.payload.ContractPowerOfAttorneyCreateRequest;
import com.enterprise.mm.entity.ContractPowerOfAttorney;
import org.springframework.stereotype.Component;

@Component
public class ContractPowerOfAttorneyMapper {
    public ContractPowerOfAttorneyResponse toResponse(ContractPowerOfAttorney entity) {
        if (entity == null) {
            return null;
        }
        ContractPowerOfAttorneyResponse dto = new ContractPowerOfAttorneyResponse();
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
        dto.setSupplierId(entity.getSupplierId());
        dto.setSupplierCode(BaseMapper.supplierCode(entity.getSupplier()));
        dto.setSupplierName(BaseMapper.supplierName(entity.getSupplier()));
        dto.setAttorneyNumber(entity.getAttorneyNumber());
        dto.setIssuedTo(entity.getIssuedTo());
        dto.setIssueDate(entity.getIssueDate());
        dto.setValidTo(entity.getValidTo());
        dto.setAmountLimit(entity.getAmountLimit());
        return dto;
    }

    public ContractPowerOfAttorney toEntity(ContractPowerOfAttorneyCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        ContractPowerOfAttorney entity = new ContractPowerOfAttorney();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setContractId(dto.getContractId());
        entity.setSupplierId(dto.getSupplierId());
        entity.setAttorneyNumber(dto.getAttorneyNumber());
        entity.setIssuedTo(dto.getIssuedTo());
        entity.setIssueDate(dto.getIssueDate());
        entity.setValidTo(dto.getValidTo());
        entity.setAmountLimit(dto.getAmountLimit());
        return entity;
    }

    public void updateEntity(ContractPowerOfAttorney entity, ContractPowerOfAttorneyUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setContractId(dto.getContractId());
        entity.setSupplierId(dto.getSupplierId());
        entity.setAttorneyNumber(dto.getAttorneyNumber());
        entity.setIssuedTo(dto.getIssuedTo());
        entity.setIssueDate(dto.getIssueDate());
        entity.setValidTo(dto.getValidTo());
        entity.setAmountLimit(dto.getAmountLimit());
    }
}