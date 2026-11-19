package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.payload.ProcurementProcedureResponse;
import org.springframework.stereotype.Component;

@Component
public class ProcurementProcedureMapper {
    public ProcurementProcedureResponse toResponse(ProcurementProcedure entity) {
        if (entity == null) {
            return null;
        }
        ProcurementProcedureResponse dto = new ProcurementProcedureResponse();
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
        dto.setProcedureNumber(entity.getProcedureNumber());
        dto.setMethodDecisionId(entity.getMethodDecisionId());
        dto.setProcurementPlanId(entity.getProcurementPlanId());
        dto.setPurchaseRequestId(entity.getPurchaseRequestId());
        dto.setMethod(entity.getMethod());
        dto.setExecutionPath(entity.getExecutionPath());
        dto.setCampaignId(entity.getCampaignId());
        dto.setRfqId(entity.getRfqId());
        dto.setDirectAwardId(entity.getDirectAwardId());
        dto.setAwardedSupplierId(entity.getAwardedSupplierId());
        dto.setPurchaseOrderId(entity.getPurchaseOrderId());
        dto.setContractId(entity.getContractId());
        dto.setAwardReference(entity.getAwardReference());
        dto.setPriceDiscoveryRfqId(entity.getPriceDiscoveryRfqId());
        dto.setRequiresPriceDiscovery(entity.isRequiresPriceDiscovery());
        dto.setFinalizedAmount(entity.getFinalizedAmount());
        dto.setFinalizedCurrencyCode(entity.getFinalizedCurrencyCode());
        dto.setPriceBasisCalculationId(entity.getPriceBasisCalculationId());
        dto.setLaunchedAt(entity.getLaunchedAt());
        dto.setCompletedAt(entity.getCompletedAt());
        dto.setCancelledAt(entity.getCancelledAt());
        dto.setCancellationReason(entity.getCancellationReason());
        return dto;
    }
}
