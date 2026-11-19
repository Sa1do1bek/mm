package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.ContractReconciliationActResponse;
import com.enterprise.mm.payload.ContractReconciliationActUpdateRequest;
import com.enterprise.mm.payload.ContractReconciliationActCreateRequest;
import com.enterprise.mm.entity.ContractReconciliationAct;
import org.springframework.stereotype.Component;

@Component
public class ContractReconciliationActMapper {
    public ContractReconciliationActResponse toResponse(ContractReconciliationAct entity) {
        if (entity == null) {
            return null;
        }
        ContractReconciliationActResponse dto = new ContractReconciliationActResponse();
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
        dto.setActNumber(entity.getActNumber());
        dto.setPeriodStart(entity.getPeriodStart());
        dto.setPeriodEnd(entity.getPeriodEnd());
        dto.setActDate(entity.getActDate());
        dto.setOpeningBalance(entity.getOpeningBalance());
        dto.setDebitAmount(entity.getDebitAmount());
        dto.setCreditAmount(entity.getCreditAmount());
        dto.setClosingBalance(entity.getClosingBalance());
        return dto;
    }

    public ContractReconciliationAct toEntity(ContractReconciliationActCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        ContractReconciliationAct entity = new ContractReconciliationAct();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setContractId(dto.getContractId());
        entity.setSupplierId(dto.getSupplierId());
        entity.setActNumber(dto.getActNumber());
        entity.setPeriodStart(dto.getPeriodStart());
        entity.setPeriodEnd(dto.getPeriodEnd());
        entity.setActDate(dto.getActDate());
        entity.setOpeningBalance(dto.getOpeningBalance());
        entity.setDebitAmount(dto.getDebitAmount());
        entity.setCreditAmount(dto.getCreditAmount());
        entity.setClosingBalance(dto.getClosingBalance());
        return entity;
    }

    public void updateEntity(ContractReconciliationAct entity, ContractReconciliationActUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setContractId(dto.getContractId());
        entity.setSupplierId(dto.getSupplierId());
        entity.setActNumber(dto.getActNumber());
        entity.setPeriodStart(dto.getPeriodStart());
        entity.setPeriodEnd(dto.getPeriodEnd());
        entity.setActDate(dto.getActDate());
        entity.setOpeningBalance(dto.getOpeningBalance());
        entity.setDebitAmount(dto.getDebitAmount());
        entity.setCreditAmount(dto.getCreditAmount());
        entity.setClosingBalance(dto.getClosingBalance());
    }
}