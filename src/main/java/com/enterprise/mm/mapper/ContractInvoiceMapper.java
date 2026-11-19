package com.enterprise.mm.mapper;

import com.enterprise.mm.common.DataQualityGuards;
import com.enterprise.mm.payload.ContractInvoiceResponse;
import com.enterprise.mm.payload.ContractInvoiceUpdateRequest;
import com.enterprise.mm.payload.ContractInvoiceCreateRequest;
import com.enterprise.mm.entity.ContractInvoice;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

@Component
public class ContractInvoiceMapper {
    public ContractInvoiceResponse toResponse(ContractInvoice entity) {
        if (entity == null) {
            return null;
        }
        ContractInvoiceResponse dto = new ContractInvoiceResponse();
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
        dto.setCommercialInstrumentType(entity.getCommercialInstrumentType());
        dto.setPurchaseOrderId(entity.getPurchaseOrderId());
        dto.setPurchaseOrderCode(BaseMapper.code(entity.getPurchaseOrder()));
        dto.setPurchaseOrderName(BaseMapper.name(entity.getPurchaseOrder()));
        dto.setSupplierId(entity.getSupplierId());
        dto.setSupplierCode(BaseMapper.supplierCode(entity.getSupplier()));
        dto.setSupplierName(BaseMapper.supplierName(entity.getSupplier()));
        dto.setInvoiceNumber(entity.getInvoiceNumber());
        dto.setInvoiceDate(entity.getInvoiceDate());
        dto.setDueDate(entity.getDueDate());
        dto.setAmount(DataQualityGuards.roundMoney(entity.getAmount(), entity.getCurrencyCode()));
        dto.setPaidAmount(DataQualityGuards.roundMoney(entity.getPaidAmount(), entity.getCurrencyCode()));
        dto.setRemainingAmount(DataQualityGuards.roundMoney(remainingAmount(entity), entity.getCurrencyCode()));
        dto.setCurrencyCode(entity.getCurrencyCode());
        return dto;
    }

    private BigDecimal remainingAmount(ContractInvoice entity) {
        if (entity.getAmount() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal paid = entity.getPaidAmount() == null ? BigDecimal.ZERO : entity.getPaidAmount();
        BigDecimal remaining = entity.getAmount().subtract(paid);
        return remaining.signum() < 0 ? BigDecimal.ZERO : remaining;
    }

    public ContractInvoice toEntity(ContractInvoiceCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        ContractInvoice entity = new ContractInvoice();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setContractId(dto.getContractId());
        entity.setCommercialInstrumentType(dto.getCommercialInstrumentType());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setSupplierId(dto.getSupplierId());
        entity.setInvoiceNumber(dto.getInvoiceNumber());
        entity.setInvoiceDate(DataQualityGuards.requireSaneDate(dto.getInvoiceDate(), "invoiceDate"));
        entity.setDueDate(DataQualityGuards.requireSaneDate(dto.getDueDate(), "dueDate"));
        DataQualityGuards.requireDateOrder(dto.getInvoiceDate(), dto.getDueDate(), "invoiceDate", "dueDate");
        entity.setAmount(DataQualityGuards.roundMoney(dto.getAmount(), dto.getCurrencyCode()));
        entity.setCurrencyCode(dto.getCurrencyCode());
        return entity;
    }

    public void updateEntity(ContractInvoice entity, ContractInvoiceUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setContractId(dto.getContractId());
        entity.setCommercialInstrumentType(dto.getCommercialInstrumentType());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setSupplierId(dto.getSupplierId());
        entity.setInvoiceNumber(dto.getInvoiceNumber());
        entity.setInvoiceDate(DataQualityGuards.requireSaneDate(dto.getInvoiceDate(), "invoiceDate"));
        entity.setDueDate(DataQualityGuards.requireSaneDate(dto.getDueDate(), "dueDate"));
        DataQualityGuards.requireDateOrder(dto.getInvoiceDate(), dto.getDueDate(), "invoiceDate", "dueDate");
        entity.setAmount(DataQualityGuards.roundMoney(dto.getAmount(), dto.getCurrencyCode()));
        entity.setCurrencyCode(dto.getCurrencyCode());
    }
}
