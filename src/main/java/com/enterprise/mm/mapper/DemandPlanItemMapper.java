package com.enterprise.mm.mapper;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.payload.DemandPlanItemResponse;
import com.enterprise.mm.payload.DemandPlanItemUpdateRequest;
import com.enterprise.mm.payload.DemandPlanItemCreateRequest;
import com.enterprise.mm.entity.DemandPlanItem;
import org.springframework.stereotype.Component;

@Component
public class DemandPlanItemMapper {
    public DemandPlanItemResponse toResponse(DemandPlanItem entity) {
        if (entity == null) {
            return null;
        }
        DemandPlanItemResponse dto = new DemandPlanItemResponse();
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
        dto.setDemandPlanName(nameOf(entity.getDemandPlan()));
        dto.setDepartmentId(entity.getDepartmentId());
        dto.setDepartmentCode(BaseMapper.code(entity.getDepartment()));
        dto.setDepartmentName(BaseMapper.name(entity.getDepartment()));
        dto.setDepartmentName(nameOf(entity.getDepartment()));
        dto.setMaterialId(entity.getMaterialId());
        dto.setMaterialCode(BaseMapper.materialCode(entity.getMaterial()));
        dto.setMaterialName(BaseMapper.materialName(entity.getMaterial()));
        dto.setMaterialName(nameOf(entity.getMaterial()));
        dto.setMaterialTypeSnapshot(entity.getMaterialTypeSnapshot());
        dto.setBudgetItemId(entity.getBudgetItemId());
        dto.setBudgetItemCode(BaseMapper.code(entity.getBudgetItem()));
        dto.setBudgetItemName(BaseMapper.name(entity.getBudgetItem()));
        dto.setBudgetItemName(nameOf(entity.getBudgetItem()));
        dto.setRequiredDate(entity.getRequiredDate());
        dto.setNeedByDate(entity.getNeedByDate());
        dto.setDemandType(entity.getDemandType());
        dto.setPriority(entity.getPriority());
        dto.setProjectCode(entity.getProjectCode());
        dto.setRepairOrderCode(entity.getRepairOrderCode());
        dto.setRequestedQuantity(entity.getRequestedQuantity());
        dto.setApprovedQuantity(entity.getApprovedQuantity());
        dto.setReservedQuantity(entity.getReservedQuantity());
        dto.setEstimatedUnitPrice(entity.getEstimatedUnitPrice());
        dto.setEstimatedAmount(entity.getEstimatedAmount());
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setConsolidatedDemandId(entity.getConsolidatedDemandId());
        dto.setConsolidatedDemandCode(BaseMapper.code(entity.getConsolidatedDemand()));
        dto.setConsolidatedDemandName(BaseMapper.name(entity.getConsolidatedDemand()));
        dto.setPurchaseRequestItemId(entity.getPurchaseRequestItemId());
        dto.setPurchaseRequestItemCode(BaseMapper.code(entity.getPurchaseRequestItem()));
        dto.setPurchaseRequestItemName(BaseMapper.name(entity.getPurchaseRequestItem()));
        return dto;
    }

    public DemandPlanItem toEntity(DemandPlanItemCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        DemandPlanItem entity = new DemandPlanItem();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setDemandPlanId(dto.getDemandPlanId());
        entity.setDepartmentId(dto.getDepartmentId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setMaterialTypeSnapshot(dto.getMaterialTypeSnapshot());
        entity.setBudgetItemId(dto.getBudgetItemId());
        entity.setRequiredDate(dto.getRequiredDate());
        entity.setNeedByDate(dto.getNeedByDate());
        entity.setDemandType(dto.getDemandType());
        entity.setPriority(dto.getPriority());
        entity.setRepairOrderCode(dto.getRepairOrderCode());
        entity.setRequestedQuantity(dto.getRequestedQuantity());
        entity.setApprovedQuantity(dto.getApprovedQuantity());
        entity.setReservedQuantity(dto.getReservedQuantity());
        entity.setEstimatedUnitPrice(dto.getEstimatedUnitPrice());
        entity.setEstimatedAmount(dto.getEstimatedAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setConsolidatedDemandId(dto.getConsolidatedDemandId());
        entity.setPurchaseRequestItemId(dto.getPurchaseRequestItemId());
        return entity;
    }

    public void updateEntity(DemandPlanItem entity, DemandPlanItemUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setDemandPlanId(dto.getDemandPlanId());
        entity.setDepartmentId(dto.getDepartmentId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setMaterialTypeSnapshot(dto.getMaterialTypeSnapshot());
        entity.setBudgetItemId(dto.getBudgetItemId());
        entity.setRequiredDate(dto.getRequiredDate());
        // Older clients omit the additive field. Only an explicit null clears it.
        if (dto.hasNeedByDate()) {
            entity.setNeedByDate(dto.getNeedByDate());
        }
        entity.setDemandType(dto.getDemandType());
        entity.setPriority(dto.getPriority());
        entity.setRepairOrderCode(dto.getRepairOrderCode());
        entity.setRequestedQuantity(dto.getRequestedQuantity());
        entity.setApprovedQuantity(dto.getApprovedQuantity());
        entity.setReservedQuantity(dto.getReservedQuantity());
        entity.setEstimatedUnitPrice(dto.getEstimatedUnitPrice());
        entity.setEstimatedAmount(dto.getEstimatedAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setConsolidatedDemandId(dto.getConsolidatedDemandId());
        entity.setPurchaseRequestItemId(dto.getPurchaseRequestItemId());
    }

    private String nameOf(BusinessEntity entity) {
        return entity == null ? null : entity.getName();
    }
}
