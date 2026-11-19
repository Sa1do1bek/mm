package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.ManagementOpportunity;
import com.enterprise.mm.entity.ManagementOpportunityEvidence;
import com.enterprise.mm.payload.ManagementOpportunityEvidenceResponse;
import com.enterprise.mm.payload.ManagementOpportunityResponse;
import com.enterprise.mm.service.ManagementOpportunityFormulaCatalog;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ManagementOpportunityMapper {
    public ManagementOpportunityResponse toResponse(ManagementOpportunity entity, List<ManagementOpportunityEvidence> evidence) {
        ManagementOpportunityResponse dto = new ManagementOpportunityResponse();
        BaseMapper.copy(entity, dto);
        dto.setOpportunityType(entity.getOpportunityType());
        dto.setCalculationRunId(entity.getCalculationRunId());
        dto.setPolicyId(entity.getPolicyId());
        dto.setSourceAggregateType(entity.getSourceAggregateType());
        dto.setSourceAggregateId(entity.getSourceAggregateId());
        dto.setSourceLineId(entity.getSourceLineId());
        dto.setMaterialId(entity.getMaterialId());
        dto.setMaterialCode(BaseMapper.materialCode(entity.getMaterial()));
        dto.setMaterialName(BaseMapper.materialName(entity.getMaterial()));
        dto.setSupplierId(entity.getSupplierId());
        dto.setSupplierCode(BaseMapper.supplierCode(entity.getSupplier()));
        dto.setSupplierName(BaseMapper.supplierName(entity.getSupplier()));
        dto.setWarehouseId(entity.getWarehouseId());
        dto.setWarehouseCode(BaseMapper.code(entity.getWarehouse()));
        dto.setWarehouseName(BaseMapper.name(entity.getWarehouse()));
        dto.setAsOfDate(entity.getAsOfDate());
        dto.setSourceCurrencyCode(entity.getSourceCurrencyCode());
        dto.setNormalizedCurrencyCode(entity.getNormalizedCurrencyCode());
        dto.setSourceAmount(entity.getSourceAmount());
        dto.setNormalizedAmount(entity.getNormalizedAmount());
        dto.setBaselineAmount(entity.getBaselineAmount());
        dto.setActualAmount(entity.getActualAmount());
        dto.setQuantity(entity.getQuantity());
        dto.setExchangeRate(entity.getExchangeRate());
        dto.setExchangeRateId(entity.getExchangeRateId());
        dto.setExchangeRateDate(entity.getExchangeRateDate());
        dto.setExchangeRateSource(entity.getExchangeRateSource());
        dto.setNormalizationQuality(entity.getNormalizationQuality());
        dto.setFormulaCode(entity.getFormulaCode());
        dto.setFormulaSnapshot(entity.getFormulaSnapshot());
        dto.setFormula(ManagementOpportunityFormulaCatalog.describe(entity.getOpportunityType()));
        dto.setConfidence(entity.getConfidence());
        dto.setDataCompleteness(entity.getDataCompleteness());
        dto.setAssignedRole(entity.getAssignedRole());
        dto.setSubmittedAt(entity.getSubmittedAt());
        dto.setSubmittedBy(entity.getSubmittedBy());
        dto.setReviewedAt(entity.getReviewedAt());
        dto.setReviewedBy(entity.getReviewedBy());
        dto.setReviewComment(entity.getReviewComment());
        dto.setApprovedAt(entity.getApprovedAt());
        dto.setApprovedBy(entity.getApprovedBy());
        dto.setRejectionReason(entity.getRejectionReason());
        dto.setEvidence(evidence == null ? List.of() : evidence.stream().map(this::toEvidenceResponse).toList());
        return dto;
    }

    public ManagementOpportunityEvidenceResponse toEvidenceResponse(ManagementOpportunityEvidence entity) {
        ManagementOpportunityEvidenceResponse dto = new ManagementOpportunityEvidenceResponse();
        BaseMapper.copy(entity, dto);
        dto.setOpportunityId(entity.getOpportunityId());
        dto.setEvidenceType(entity.getEvidenceType());
        dto.setSourceEntityType(entity.getSourceEntityType());
        dto.setSourceEntityId(entity.getSourceEntityId());
        dto.setCapturedSnapshot(entity.getCapturedSnapshot());
        return dto;
    }
}
