package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.payload.SupplierQuotationResponse;
import com.enterprise.mm.payload.SupplierQuotationUpdateRequest;
import com.enterprise.mm.payload.SupplierQuotationCreateRequest;
import org.springframework.stereotype.Component;

@Component
public class SupplierQuotationMapper {
    public SupplierQuotationResponse toResponse(SupplierQuotation entity) {
        if (entity == null) {
            return null;
        }
        SupplierQuotationResponse dto = new SupplierQuotationResponse();
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
        dto.setSupplierId(entity.getSupplierId());
        dto.setSupplierCode(BaseMapper.supplierCode(entity.getSupplier()));
        dto.setSupplierName(BaseMapper.supplierName(entity.getSupplier()));
        dto.setTotalAmount(entity.getTotalAmount());
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setSubmittedAt(entity.getSubmittedAt());
        dto.setSelected(entity.isSelected());
        dto.setTechnicalScore(entity.getTechnicalScore());
        dto.setDeliveryScore(entity.getDeliveryScore());
        dto.setPriceScore(entity.getPriceScore());
        dto.setTotalScore(entity.getTotalScore());
        dto.setSelectionReason(entity.getSelectionReason());
        if (dto.getRfqId() == null && entity.getRfq() != null) {
            dto.setRfqId(entity.getRfq().getId());
        }
        if (dto.getSupplierId() == null && entity.getSupplier() != null) {
            dto.setSupplierId(entity.getSupplier().getId());
        }
        return dto;
    }

    public SupplierQuotation toEntity(SupplierQuotationCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        SupplierQuotation entity = new SupplierQuotation();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setRfqId(dto.getRfqId());
        entity.setSupplierId(dto.getSupplierId());
        entity.setTotalAmount(dto.getTotalAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setSubmittedAt(dto.getSubmittedAt());
        entity.setSelected(dto.isSelected());
        entity.setTechnicalScore(dto.getTechnicalScore());
        entity.setDeliveryScore(dto.getDeliveryScore());
        entity.setPriceScore(dto.getPriceScore());
        entity.setTotalScore(dto.getTotalScore());
        entity.setSelectionReason(dto.getSelectionReason());
        entity.setRfq(null);
        entity.setSupplier(null);
        return entity;
    }

    public void updateEntity(SupplierQuotation entity, SupplierQuotationUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setRfqId(dto.getRfqId());
        entity.setSupplierId(dto.getSupplierId());
        entity.setTotalAmount(dto.getTotalAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setSubmittedAt(dto.getSubmittedAt());
        entity.setSelected(dto.isSelected());
        entity.setTechnicalScore(dto.getTechnicalScore());
        entity.setDeliveryScore(dto.getDeliveryScore());
        entity.setPriceScore(dto.getPriceScore());
        entity.setTotalScore(dto.getTotalScore());
        entity.setSelectionReason(dto.getSelectionReason());
        entity.setRfq(null);
        entity.setSupplier(null);
    }
}
