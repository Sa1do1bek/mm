package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.ProcurementMethodDecision;
import com.enterprise.mm.payload.ProcurementMethodDecisionCreateRequest;
import com.enterprise.mm.payload.ProcurementMethodDecisionResponse;
import com.enterprise.mm.payload.ProcurementMethodDecisionUpdateRequest;
import org.springframework.stereotype.Component;

@Component
public class ProcurementMethodDecisionMapper {
    public ProcurementMethodDecision toEntity(ProcurementMethodDecisionCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        ProcurementMethodDecision entity = new ProcurementMethodDecision();
        copyRequest(dto, entity);
        return entity;
    }

    public void updateEntity(ProcurementMethodDecision entity, ProcurementMethodDecisionUpdateRequest dto) {
        if (entity != null && dto != null) {
            copyRequest(dto, entity);
        }
    }

    public ProcurementMethodDecisionResponse toResponse(ProcurementMethodDecision entity) {
        if (entity == null) {
            return null;
        }
        ProcurementMethodDecisionResponse dto = new ProcurementMethodDecisionResponse();
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
        dto.setDecisionNumber(entity.getDecisionNumber());
        dto.setPurchaseRequestId(entity.getPurchaseRequestId());
        dto.setSelectedMethod(entity.getSelectedMethod());
        dto.setJustification(entity.getJustification());
        dto.setEstimatedAmount(entity.getEstimatedAmount());
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setPotentialSupplierCount(entity.getPotentialSupplierCount());
        dto.setCommissionId(entity.getCommissionId());
        dto.setMarketResearchAttachmentId(entity.getMarketResearchAttachmentId());
        dto.setTechnicalSpecificationAttachmentId(entity.getTechnicalSpecificationAttachmentId());
        dto.setExceptionReason(entity.getExceptionReason());
        dto.setSubmittedAt(entity.getSubmittedAt());
        dto.setApprovedAt(entity.getApprovedAt());
        dto.setApprovedBy(entity.getApprovedBy());
        dto.setRejectedAt(entity.getRejectedAt());
        dto.setRejectedBy(entity.getRejectedBy());
        dto.setRejectionReason(entity.getRejectionReason());
        return dto;
    }

    private void copyRequest(ProcurementMethodDecisionCreateRequest dto, ProcurementMethodDecision entity) {
        if (dto.getName() != null) {
            entity.setName(dto.getName());
        }
        if (dto.getDescription() != null) {
            entity.setDescription(dto.getDescription());
        }
        if (dto.getPurchaseRequestId() != null) {
            entity.setPurchaseRequestId(dto.getPurchaseRequestId());
        }
        if (dto.getSelectedMethod() != null) {
            entity.setSelectedMethod(dto.getSelectedMethod());
        }
        if (dto.getJustification() != null) {
            entity.setJustification(dto.getJustification());
        }
        if (dto.getEstimatedAmount() != null) {
            entity.setEstimatedAmount(dto.getEstimatedAmount());
        }
        if (dto.getCurrencyCode() != null) {
            entity.setCurrencyCode(dto.getCurrencyCode());
        }
        if (dto.getPotentialSupplierCount() != null) {
            entity.setPotentialSupplierCount(dto.getPotentialSupplierCount());
        }
        entity.setCommissionId(dto.getCommissionId());
        if (dto.getMarketResearchAttachmentId() != null) {
            entity.setMarketResearchAttachmentId(dto.getMarketResearchAttachmentId());
        }
        if (dto.getTechnicalSpecificationAttachmentId() != null) {
            entity.setTechnicalSpecificationAttachmentId(dto.getTechnicalSpecificationAttachmentId());
        }
        if (dto.getExceptionReason() != null) {
            entity.setExceptionReason(dto.getExceptionReason());
        }
    }
}
