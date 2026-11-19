package com.enterprise.mm.mapper;

import com.enterprise.mm.common.DataQualityGuards;
import com.enterprise.mm.payload.RFQResponse;
import com.enterprise.mm.payload.RFQUpdateRequest;
import com.enterprise.mm.payload.RFQCreateRequest;
import com.enterprise.mm.entity.RFQ;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class RFQMapper {
    public RFQResponse toResponse(RFQ entity) {
        return toResponse(entity, List.of());
    }

    public RFQResponse toResponse(RFQ entity, List<UUID> invitedSupplierIds) {
        if (entity == null) {
            return null;
        }
        RFQResponse dto = new RFQResponse();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setCompanyId(entity.getCompanyId());
        dto.setName(entity.getName());
        dto.setStatus(entity.getStatus());
        dto.setDescription(entity.getDescription());
        dto.setDeleted(entity.isDeleted());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setUpdatedBy(entity.getUpdatedBy());
        dto.setVersion(entity.getVersion());
        dto.setPurchaseRequestId(entity.getPurchaseRequestId());
        dto.setPurchaseRequestCode(BaseMapper.code(entity.getPurchaseRequest()));
        dto.setPurchaseRequestName(BaseMapper.name(entity.getPurchaseRequest()));
        dto.setPurpose(entity.getPurpose());
        dto.setProcurementProcedureId(entity.getProcurementProcedureId());
        dto.setSourceRfqId(entity.getSourceRfqId());
        dto.setPriceBasisCalculationId(entity.getPriceBasisCalculationId());
        dto.setDueDate(entity.getDueDate());
        dto.setTenderProcedureType(entity.getTenderProcedureType());
        dto.setSelectionRule(entity.getSelectionRule());
        dto.setMinQuotationsRequired(entity.getMinQuotationsRequired());
        dto.setAggregatedTender(entity.isAggregatedTender());
        dto.setAggregationMode(entity.getAggregationMode());
        dto.setMaterialGroupId(entity.getMaterialGroupId());
        dto.setSourcePurchaseRequestCount(entity.getSourcePurchaseRequestCount());
        dto.setSourceItemCount(entity.getSourceItemCount());
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setResponsibleBuyer(entity.getResponsibleBuyer());
        dto.setResponseInstructions(entity.getResponseInstructions());
        dto.setPackageLanguage(entity.getPackageLanguage());
        dto.setActivePublishedVersionId(entity.getActivePublishedVersionId());
        dto.setPublishedAt(entity.getPublishedAt());
        dto.setPublishedBy(entity.getPublishedBy());
        dto.setClosedAt(entity.getClosedAt());
        dto.setInvitedSupplierIds(invitedSupplierIds == null ? List.of() : List.copyOf(invitedSupplierIds));
        if (dto.getPurchaseRequestId() == null && entity.getPurchaseRequest() != null) {
            dto.setPurchaseRequestId(entity.getPurchaseRequest().getId());
        }
        return dto;
    }

    public RFQ toEntity(RFQCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        RFQ entity = new RFQ();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setPurchaseRequestId(dto.getPurchaseRequestId());
        entity.setPurpose(dto.getPurpose());
        entity.setProcurementProcedureId(dto.getProcurementProcedureId());
        entity.setSourceRfqId(dto.getSourceRfqId());
        entity.setDueDate(DataQualityGuards.requireSaneDate(dto.getDueDate(), "dueDate"));
        entity.setTenderProcedureType(dto.getTenderProcedureType());
        entity.setSelectionRule(dto.getSelectionRule());
        entity.setMinQuotationsRequired(dto.getMinQuotationsRequired());
        entity.setResponsibleBuyer(dto.getResponsibleBuyer());
        entity.setResponseInstructions(dto.getResponseInstructions());
        entity.setPackageLanguage(dto.getPackageLanguage());
        entity.setPurchaseRequest(null);
        return entity;
    }

    public void updateEntity(RFQ entity, RFQUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setPurchaseRequestId(dto.getPurchaseRequestId());
        entity.setPurpose(dto.getPurpose());
        entity.setProcurementProcedureId(dto.getProcurementProcedureId());
        entity.setSourceRfqId(dto.getSourceRfqId());
        entity.setDueDate(DataQualityGuards.requireSaneDate(dto.getDueDate(), "dueDate"));
        entity.setTenderProcedureType(dto.getTenderProcedureType());
        entity.setSelectionRule(dto.getSelectionRule());
        entity.setMinQuotationsRequired(dto.getMinQuotationsRequired());
        entity.setResponsibleBuyer(dto.getResponsibleBuyer());
        entity.setResponseInstructions(dto.getResponseInstructions());
        entity.setPackageLanguage(dto.getPackageLanguage());
        entity.setPurchaseRequest(null);
    }
}
