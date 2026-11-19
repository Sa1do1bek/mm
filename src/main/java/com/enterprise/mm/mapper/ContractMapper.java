package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.ContractResponse;
import com.enterprise.mm.payload.ContractUpdateRequest;
import com.enterprise.mm.payload.ContractCreateRequest;
import com.enterprise.mm.entity.Contract;
import org.springframework.stereotype.Component;

@Component
public class ContractMapper {
    public ContractResponse toResponse(Contract entity) {
        if (entity == null) {
            return null;
        }
        ContractResponse dto = new ContractResponse();
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
        dto.setContractNumber(entity.getContractNumber());
        dto.setStartDate(entity.getStartDate());
        dto.setEndDate(entity.getEndDate());
        dto.setLimitAmount(entity.getLimitAmount());
        dto.setUsedAmount(entity.getUsedAmount());
        dto.setServiceUsedAmount(entity.getServiceUsedAmount());
        dto.setRemainingAmount((entity.getLimitAmount() == null ? java.math.BigDecimal.ZERO : entity.getLimitAmount())
            .subtract(entity.getUsedAmount() == null ? java.math.BigDecimal.ZERO : entity.getUsedAmount()));
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setSourceRfqId(entity.getSourceRfqId());
        dto.setSourceQuotationId(entity.getSourceQuotationId());
        return dto;
    }

    public Contract toEntity(ContractCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        Contract entity = new Contract();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setSupplierId(dto.getSupplierId());
        entity.setContractNumber(dto.getContractNumber());
        entity.setStartDate(dto.getStartDate());
        entity.setEndDate(dto.getEndDate());
        entity.setLimitAmount(dto.getLimitAmount());
        // usedAmount / serviceUsedAmount are system ledger fields — initialized on create, not from client
        entity.setCurrencyCode(dto.getCurrencyCode());
        return entity;
    }

    public void updateEntity(Contract entity, ContractUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setSupplierId(dto.getSupplierId());
        entity.setContractNumber(dto.getContractNumber());
        entity.setStartDate(dto.getStartDate());
        entity.setEndDate(dto.getEndDate());
        entity.setLimitAmount(dto.getLimitAmount());
        // Do not map usedAmount / serviceUsedAmount — ledger is updated only via consumeLimit
        entity.setCurrencyCode(dto.getCurrencyCode());
    }
}
