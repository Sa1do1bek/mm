package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.ManagementOpportunityPolicy;
import com.enterprise.mm.payload.ManagementOpportunityPolicyCreateRequest;
import com.enterprise.mm.payload.ManagementOpportunityPolicyResponse;
import com.enterprise.mm.payload.ManagementOpportunityPolicyUpdateRequest;
import org.springframework.stereotype.Component;

@Component
public class ManagementOpportunityPolicyMapper {
    public ManagementOpportunityPolicyResponse toResponse(ManagementOpportunityPolicy entity) {
        if (entity == null) {
            return null;
        }
        ManagementOpportunityPolicyResponse dto = new ManagementOpportunityPolicyResponse();
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
        dto.setOpportunityType(entity.getOpportunityType());
        dto.setActive(entity.isActive());
        dto.setEffectiveFrom(entity.getEffectiveFrom());
        dto.setEffectiveTo(entity.getEffectiveTo());
        dto.setWarehouseId(entity.getWarehouseId());
        dto.setWarehouseCode(BaseMapper.code(entity.getWarehouse()));
        dto.setWarehouseName(BaseMapper.name(entity.getWarehouse()));
        dto.setMaterialGroupId(entity.getMaterialGroupId());
        dto.setMaterialGroupCode(BaseMapper.code(entity.getMaterialGroup()));
        dto.setMaterialGroupName(BaseMapper.name(entity.getMaterialGroup()));
        dto.setSupplierId(entity.getSupplierId());
        dto.setSupplierCode(BaseMapper.supplierCode(entity.getSupplier()));
        dto.setSupplierName(BaseMapper.supplierName(entity.getSupplier()));
        dto.setAnnualCarryingCostRate(entity.getAnnualCarryingCostRate());
        dto.setDailyImpactRate(entity.getDailyImpactRate());
        dto.setAttributableShare(entity.getAttributableShare());
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setActivatedAt(entity.getActivatedAt());
        dto.setActivatedBy(entity.getActivatedBy());
        return dto;
    }

    public ManagementOpportunityPolicy toEntity(ManagementOpportunityPolicyCreateRequest request) {
        if (request == null) {
            return null;
        }
        ManagementOpportunityPolicy entity = new ManagementOpportunityPolicy();
        updateFields(entity, request);
        return entity;
    }

    public void updateEntity(ManagementOpportunityPolicy entity, ManagementOpportunityPolicyUpdateRequest request) {
        updateFields(entity, request);
    }

    private void updateFields(ManagementOpportunityPolicy entity, ManagementOpportunityPolicyCreateRequest request) {
        entity.setCode(request.getCode());
        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        entity.setOpportunityType(request.getOpportunityType());
        entity.setEffectiveFrom(request.getEffectiveFrom());
        entity.setEffectiveTo(request.getEffectiveTo());
        entity.setWarehouseId(request.getWarehouseId());
        entity.setMaterialGroupId(request.getMaterialGroupId());
        entity.setSupplierId(request.getSupplierId());
        entity.setAnnualCarryingCostRate(request.getAnnualCarryingCostRate());
        entity.setDailyImpactRate(request.getDailyImpactRate());
        entity.setAttributableShare(request.getAttributableShare());
        entity.setCurrencyCode(request.getCurrencyCode());
    }
}
