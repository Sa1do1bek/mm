package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.TenderEvaluationProtocolResponse;
import com.enterprise.mm.payload.TenderEvaluationProtocolUpdateRequest;
import com.enterprise.mm.payload.TenderEvaluationProtocolCreateRequest;
import com.enterprise.mm.entity.TenderEvaluationProtocol;
import org.springframework.stereotype.Component;

@Component
public class TenderEvaluationProtocolMapper {
    public TenderEvaluationProtocolResponse toResponse(TenderEvaluationProtocol entity) {
        if (entity == null) {
            return null;
        }
        TenderEvaluationProtocolResponse dto = new TenderEvaluationProtocolResponse();
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
        dto.setProtocolNumber(entity.getProtocolNumber());
        dto.setEvaluatedAt(entity.getEvaluatedAt());
        dto.setSelectionRule(entity.getSelectionRule());
        dto.setRecommendedQuotationId(entity.getRecommendedQuotationId());
        dto.setRecommendedQuotationCode(BaseMapper.code(entity.getRecommendedQuotation()));
        dto.setRecommendedQuotationName(BaseMapper.name(entity.getRecommendedQuotation()));
        dto.setRecommendedSupplierId(entity.getRecommendedSupplierId());
        dto.setRecommendedSupplierCode(BaseMapper.supplierCode(entity.getRecommendedSupplier()));
        dto.setRecommendedSupplierName(BaseMapper.supplierName(entity.getRecommendedSupplier()));
        dto.setRecommendationReason(entity.getRecommendationReason());
        dto.setScoringSummary(entity.getScoringSummary());
        dto.setConflictSummary(entity.getConflictSummary());
        dto.setApprovedBy(entity.getApprovedBy());
        dto.setApprovedAt(entity.getApprovedAt());
        dto.setPostedAt(entity.getPostedAt());
        return dto;
    }

    public TenderEvaluationProtocol toEntity(TenderEvaluationProtocolCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        TenderEvaluationProtocol entity = new TenderEvaluationProtocol();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setRfqId(dto.getRfqId());
        entity.setProtocolNumber(dto.getProtocolNumber());
        entity.setEvaluatedAt(dto.getEvaluatedAt());
        entity.setSelectionRule(dto.getSelectionRule());
        entity.setRecommendedQuotationId(dto.getRecommendedQuotationId());
        entity.setRecommendedSupplierId(dto.getRecommendedSupplierId());
        entity.setRecommendationReason(dto.getRecommendationReason());
        entity.setScoringSummary(dto.getScoringSummary());
        entity.setConflictSummary(dto.getConflictSummary());
        entity.setApprovedBy(dto.getApprovedBy());
        entity.setApprovedAt(dto.getApprovedAt());
        entity.setPostedAt(dto.getPostedAt());
        return entity;
    }

    public void updateEntity(TenderEvaluationProtocol entity, TenderEvaluationProtocolUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setRfqId(dto.getRfqId());
        entity.setProtocolNumber(dto.getProtocolNumber());
        entity.setEvaluatedAt(dto.getEvaluatedAt());
        entity.setSelectionRule(dto.getSelectionRule());
        entity.setRecommendedQuotationId(dto.getRecommendedQuotationId());
        entity.setRecommendedSupplierId(dto.getRecommendedSupplierId());
        entity.setRecommendationReason(dto.getRecommendationReason());
        entity.setScoringSummary(dto.getScoringSummary());
        entity.setConflictSummary(dto.getConflictSummary());
        entity.setApprovedBy(dto.getApprovedBy());
        entity.setApprovedAt(dto.getApprovedAt());
        entity.setPostedAt(dto.getPostedAt());
    }
}