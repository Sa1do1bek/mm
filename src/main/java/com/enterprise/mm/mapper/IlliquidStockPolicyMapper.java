package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.IlliquidStockPolicy;
import com.enterprise.mm.payload.IlliquidStockPolicyCreateRequest;
import com.enterprise.mm.payload.IlliquidStockPolicyResponse;
import com.enterprise.mm.payload.IlliquidStockPolicyUpdateRequest;
import org.springframework.stereotype.Component;

@Component
public class IlliquidStockPolicyMapper {
    public IlliquidStockPolicyResponse toResponse(IlliquidStockPolicy entity) {
        if (entity == null) {
            return null;
        }
        IlliquidStockPolicyResponse dto = new IlliquidStockPolicyResponse();
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
        dto.setLegalEntityCode(entity.getLegalEntityCode());
        dto.setWarehouseId(entity.getWarehouseId());
        dto.setMaterialCategory(entity.getMaterialCategory());
        dto.setMaterialType(entity.getMaterialType());
        dto.setAgingDays(entity.getAgingDays());
        dto.setExcessDays(entity.getExcessDays());
        dto.setEffectiveFrom(entity.getEffectiveFrom());
        dto.setEffectiveTo(entity.getEffectiveTo());
        dto.setActive(entity.isActive());
        dto.setActivatedAt(entity.getActivatedAt());
        dto.setActivatedBy(entity.getActivatedBy());
        return dto;
    }

    public IlliquidStockPolicy toEntity(IlliquidStockPolicyCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        IlliquidStockPolicy entity = new IlliquidStockPolicy();
        updateFields(entity, dto);
        return entity;
    }

    public void updateEntity(IlliquidStockPolicy entity, IlliquidStockPolicyUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        updateFields(entity, dto);
    }

    private void updateFields(IlliquidStockPolicy entity, IlliquidStockPolicyCreateRequest dto) {
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setLegalEntityCode(dto.getLegalEntityCode());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setMaterialCategory(dto.getMaterialCategory());
        entity.setMaterialType(dto.getMaterialType());
        entity.setAgingDays(dto.getAgingDays());
        entity.setExcessDays(dto.getExcessDays());
        entity.setEffectiveFrom(dto.getEffectiveFrom());
        entity.setEffectiveTo(dto.getEffectiveTo());
    }
}
