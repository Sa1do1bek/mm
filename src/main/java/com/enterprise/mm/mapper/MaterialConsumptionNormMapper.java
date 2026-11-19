package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.MaterialConsumptionNormResponse;
import com.enterprise.mm.payload.MaterialConsumptionNormUpdateRequest;
import com.enterprise.mm.payload.MaterialConsumptionNormCreateRequest;
import com.enterprise.mm.entity.MaterialConsumptionNorm;
import org.springframework.stereotype.Component;

@Component
public class MaterialConsumptionNormMapper {
    public MaterialConsumptionNormResponse toResponse(MaterialConsumptionNorm entity) {
        if (entity == null) {
            return null;
        }
        MaterialConsumptionNormResponse dto = new MaterialConsumptionNormResponse();
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
        dto.setMaterialId(entity.getMaterialId());
        dto.setEquipmentId(entity.getEquipmentId());
        dto.setDepartmentId(entity.getDepartmentId());
        dto.setProductCode(entity.getProductCode());
        dto.setOperationCode(entity.getOperationCode());
        dto.setNormType(entity.getNormType());
        dto.setBaseQuantity(entity.getBaseQuantity());
        dto.setOutputQuantity(entity.getOutputQuantity());
        dto.setUnitOfMeasureId(entity.getUnitOfMeasureId());
        dto.setLossPercent(entity.getLossPercent());
        dto.setEffectiveFrom(entity.getEffectiveFrom());
        dto.setEffectiveTo(entity.getEffectiveTo());
        return dto;
    }

    public MaterialConsumptionNorm toEntity(MaterialConsumptionNormCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        MaterialConsumptionNorm entity = new MaterialConsumptionNorm();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setMaterialId(dto.getMaterialId());
        entity.setEquipmentId(dto.getEquipmentId());
        entity.setDepartmentId(dto.getDepartmentId());
        entity.setProductCode(dto.getProductCode());
        entity.setOperationCode(dto.getOperationCode());
        entity.setNormType(dto.getNormType());
        entity.setBaseQuantity(dto.getBaseQuantity());
        entity.setOutputQuantity(dto.getOutputQuantity());
        entity.setUnitOfMeasureId(dto.getUnitOfMeasureId());
        entity.setLossPercent(dto.getLossPercent());
        entity.setEffectiveFrom(dto.getEffectiveFrom());
        entity.setEffectiveTo(dto.getEffectiveTo());
        return entity;
    }

    public void updateEntity(MaterialConsumptionNorm entity, MaterialConsumptionNormUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setMaterialId(dto.getMaterialId());
        entity.setEquipmentId(dto.getEquipmentId());
        entity.setDepartmentId(dto.getDepartmentId());
        entity.setProductCode(dto.getProductCode());
        entity.setOperationCode(dto.getOperationCode());
        entity.setNormType(dto.getNormType());
        entity.setBaseQuantity(dto.getBaseQuantity());
        entity.setOutputQuantity(dto.getOutputQuantity());
        entity.setUnitOfMeasureId(dto.getUnitOfMeasureId());
        entity.setLossPercent(dto.getLossPercent());
        entity.setEffectiveFrom(dto.getEffectiveFrom());
        entity.setEffectiveTo(dto.getEffectiveTo());
    }
}