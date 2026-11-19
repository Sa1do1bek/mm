package com.enterprise.mm.mapper;

import com.enterprise.mm.common.DataQualityGuards;
import com.enterprise.mm.payload.ContractPaymentResponse;
import com.enterprise.mm.payload.ContractPaymentUpdateRequest;
import com.enterprise.mm.payload.ContractPaymentCreateRequest;
import com.enterprise.mm.entity.ContractPayment;
import org.springframework.stereotype.Component;

@Component
public class ContractPaymentMapper {
    public ContractPaymentResponse toResponse(ContractPayment entity) {
        if (entity == null) {
            return null;
        }
        ContractPaymentResponse dto = new ContractPaymentResponse();
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
        dto.setContractInvoiceId(entity.getContractInvoiceId());
        dto.setContractInvoiceCode(BaseMapper.code(entity.getContractInvoice()));
        dto.setContractInvoiceNumber(entity.getContractInvoice() == null ? null : entity.getContractInvoice().getInvoiceNumber());
        dto.setPurchaseOrderId(entity.getPurchaseOrderId());
        dto.setPurchaseOrderCode(BaseMapper.code(entity.getPurchaseOrder()));
        dto.setPurchaseOrderName(BaseMapper.name(entity.getPurchaseOrder()));
        dto.setSupplierId(entity.getSupplierId());
        dto.setSupplierCode(BaseMapper.supplierCode(entity.getSupplier()));
        dto.setSupplierName(BaseMapper.supplierName(entity.getSupplier()));
        dto.setSourceDocumentType(entity.getSourceDocumentType());
        dto.setSourceDocumentId(entity.getSourceDocumentId());
        dto.setTreasuryReference(entity.getTreasuryReference());
        dto.setPaymentNumber(entity.getPaymentNumber());
        dto.setPaymentDate(entity.getPaymentDate());
        dto.setAmount(DataQualityGuards.roundMoney(entity.getAmount(), entity.getCurrencyCode()));
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setPaymentMethod(entity.getPaymentMethod());
        dto.setBankReference(entity.getBankReference());
        return dto;
    }

    public ContractPayment toEntity(ContractPaymentCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        ContractPayment entity = new ContractPayment();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setContractId(dto.getContractId());
        entity.setContractInvoiceId(dto.getContractInvoiceId());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setSupplierId(dto.getSupplierId());
        entity.setSourceDocumentType(dto.getSourceDocumentType());
        entity.setSourceDocumentId(dto.getSourceDocumentId());
        entity.setTreasuryReference(dto.getTreasuryReference());
        entity.setPaymentNumber(dto.getPaymentNumber());
        entity.setPaymentDate(DataQualityGuards.requireSaneDate(dto.getPaymentDate(), "paymentDate"));
        entity.setAmount(DataQualityGuards.roundMoney(dto.getAmount(), dto.getCurrencyCode()));
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setPaymentMethod(dto.getPaymentMethod());
        entity.setBankReference(dto.getBankReference());
        return entity;
    }

    public void updateEntity(ContractPayment entity, ContractPaymentUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setPaymentNumber(dto.getPaymentNumber());
        entity.setPaymentDate(DataQualityGuards.requireSaneDate(dto.getPaymentDate(), "paymentDate"));
        entity.setAmount(DataQualityGuards.roundMoney(dto.getAmount(), dto.getCurrencyCode()));
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setPaymentMethod(dto.getPaymentMethod());
        entity.setBankReference(dto.getBankReference());
    }
}
