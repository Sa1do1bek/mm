package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.ConsolidatedDemandResponse;
import com.enterprise.mm.payload.ConsolidatedDemandUpdateRequest;
import com.enterprise.mm.payload.ConsolidatedDemandCreateRequest;
import com.enterprise.mm.entity.ConsolidatedDemand;
import org.springframework.stereotype.Component;

@Component
public class ConsolidatedDemandMapper {
    public ConsolidatedDemandResponse toResponse(ConsolidatedDemand entity) {
        if (entity == null) {
            return null;
        }
        ConsolidatedDemandResponse dto = new ConsolidatedDemandResponse();
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
        dto.setDemandPlanId(entity.getDemandPlanId());
        dto.setDemandPlanCode(BaseMapper.code(entity.getDemandPlan()));
        dto.setDemandPlanName(BaseMapper.name(entity.getDemandPlan()));
        dto.setMaterialId(entity.getMaterialId());
        dto.setMaterialCode(BaseMapper.materialCode(entity.getMaterial()));
        dto.setMaterialName(BaseMapper.materialName(entity.getMaterial()));
        dto.setBudgetItemId(entity.getBudgetItemId());
        dto.setBudgetItemCode(BaseMapper.code(entity.getBudgetItem()));
        dto.setBudgetItemName(BaseMapper.name(entity.getBudgetItem()));
        dto.setPeriodStart(entity.getPeriodStart());
        dto.setPeriodEnd(entity.getPeriodEnd());
        dto.setHighestPriority(entity.getHighestPriority());
        dto.setTotalRequestedQuantity(entity.getTotalRequestedQuantity());
        dto.setTotalApprovedQuantity(entity.getTotalApprovedQuantity());
        dto.setTotalEstimatedAmount(entity.getTotalEstimatedAmount());
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setSourceItemCount(entity.getSourceItemCount());
        dto.setPurchaseRequestId(entity.getPurchaseRequestId());
        dto.setPurchaseRequestCode(BaseMapper.code(entity.getPurchaseRequest()));
        dto.setPurchaseRequestName(BaseMapper.name(entity.getPurchaseRequest()));
        dto.setConsolidatedAt(entity.getConsolidatedAt());
        return dto;
    }

    public ConsolidatedDemand toEntity(ConsolidatedDemandCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        ConsolidatedDemand entity = new ConsolidatedDemand();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setDemandPlanId(dto.getDemandPlanId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setBudgetItemId(dto.getBudgetItemId());
        entity.setPeriodStart(dto.getPeriodStart());
        entity.setPeriodEnd(dto.getPeriodEnd());
        entity.setHighestPriority(dto.getHighestPriority());
        entity.setTotalRequestedQuantity(dto.getTotalRequestedQuantity());
        entity.setTotalApprovedQuantity(dto.getTotalApprovedQuantity());
        entity.setTotalEstimatedAmount(dto.getTotalEstimatedAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setSourceItemCount(dto.getSourceItemCount());
        entity.setPurchaseRequestId(dto.getPurchaseRequestId());
        entity.setConsolidatedAt(dto.getConsolidatedAt());
        return entity;
    }

    public void updateEntity(ConsolidatedDemand entity, ConsolidatedDemandUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setDemandPlanId(dto.getDemandPlanId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setBudgetItemId(dto.getBudgetItemId());
        entity.setPeriodStart(dto.getPeriodStart());
        entity.setPeriodEnd(dto.getPeriodEnd());
        entity.setHighestPriority(dto.getHighestPriority());
        entity.setTotalRequestedQuantity(dto.getTotalRequestedQuantity());
        entity.setTotalApprovedQuantity(dto.getTotalApprovedQuantity());
        entity.setTotalEstimatedAmount(dto.getTotalEstimatedAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setSourceItemCount(dto.getSourceItemCount());
        entity.setPurchaseRequestId(dto.getPurchaseRequestId());
        entity.setConsolidatedAt(dto.getConsolidatedAt());
    }
}