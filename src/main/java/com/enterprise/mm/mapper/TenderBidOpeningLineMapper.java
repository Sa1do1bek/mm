package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.TenderBidOpeningLineResponse;
import com.enterprise.mm.payload.TenderBidOpeningLineUpdateRequest;
import com.enterprise.mm.payload.TenderBidOpeningLineCreateRequest;
import com.enterprise.mm.entity.TenderBidOpeningLine;
import org.springframework.stereotype.Component;

@Component
public class TenderBidOpeningLineMapper {
    public TenderBidOpeningLineResponse toResponse(TenderBidOpeningLine entity) {
        if (entity == null) {
            return null;
        }
        TenderBidOpeningLineResponse dto = new TenderBidOpeningLineResponse();
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
        dto.setProtocolId(entity.getProtocolId());
        dto.setProtocolCode(BaseMapper.code(entity.getProtocol()));
        dto.setProtocolName(BaseMapper.name(entity.getProtocol()));
        dto.setRfqId(entity.getRfqId());
        dto.setRfqCode(BaseMapper.code(entity.getRfq()));
        dto.setRfqName(BaseMapper.name(entity.getRfq()));
        dto.setQuotationId(entity.getQuotationId());
        dto.setQuotationCode(BaseMapper.code(entity.getQuotation()));
        dto.setQuotationName(BaseMapper.name(entity.getQuotation()));
        dto.setSupplierId(entity.getSupplierId());
        dto.setSupplierCode(BaseMapper.supplierCode(entity.getSupplier()));
        dto.setSupplierName(BaseMapper.supplierName(entity.getSupplier()));
        dto.setSubmittedAt(entity.getSubmittedAt());
        dto.setTotalAmount(entity.getTotalAmount());
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setAcceptedForEvaluation(entity.isAcceptedForEvaluation());
        dto.setRejectionReason(entity.getRejectionReason());
        return dto;
    }

    public TenderBidOpeningLine toEntity(TenderBidOpeningLineCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        TenderBidOpeningLine entity = new TenderBidOpeningLine();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setProtocolId(dto.getProtocolId());
        entity.setRfqId(dto.getRfqId());
        entity.setQuotationId(dto.getQuotationId());
        entity.setSupplierId(dto.getSupplierId());
        entity.setSubmittedAt(dto.getSubmittedAt());
        entity.setTotalAmount(dto.getTotalAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setAcceptedForEvaluation(dto.isAcceptedForEvaluation());
        entity.setRejectionReason(dto.getRejectionReason());
        return entity;
    }

    public void updateEntity(TenderBidOpeningLine entity, TenderBidOpeningLineUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setProtocolId(dto.getProtocolId());
        entity.setRfqId(dto.getRfqId());
        entity.setQuotationId(dto.getQuotationId());
        entity.setSupplierId(dto.getSupplierId());
        entity.setSubmittedAt(dto.getSubmittedAt());
        entity.setTotalAmount(dto.getTotalAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setAcceptedForEvaluation(dto.isAcceptedForEvaluation());
        entity.setRejectionReason(dto.getRejectionReason());
    }
}