package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.LandedCostAllocationLineResponse;
import com.enterprise.mm.payload.LandedCostAllocationLineUpdateRequest;
import com.enterprise.mm.payload.LandedCostAllocationLineCreateRequest;
import com.enterprise.mm.entity.LandedCostAllocationLine;
import org.springframework.stereotype.Component;

@Component
public class LandedCostAllocationLineMapper {
    public LandedCostAllocationLineResponse toResponse(LandedCostAllocationLine entity) {
        if (entity == null) {
            return null;
        }
        LandedCostAllocationLineResponse dto = new LandedCostAllocationLineResponse();
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
        dto.setAllocationDocumentId(entity.getAllocationDocumentId());
        dto.setMaterialId(entity.getMaterialId());
        dto.setWarehouseId(entity.getWarehouseId());
        dto.setStorageLocationId(entity.getStorageLocationId());
        dto.setStockBatchId(entity.getStockBatchId());
        dto.setAllocationBase(entity.getAllocationBase());
        dto.setQuantity(entity.getQuantity());
        dto.setAllocatedAmount(entity.getAllocatedAmount());
        dto.setAllocatedBaseAmount(entity.getAllocatedBaseAmount());
        dto.setUnitCostIncrease(entity.getUnitCostIncrease());
        return dto;
    }

    public LandedCostAllocationLine toEntity(LandedCostAllocationLineCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        LandedCostAllocationLine entity = new LandedCostAllocationLine();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setAllocationDocumentId(dto.getAllocationDocumentId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setStorageLocationId(dto.getStorageLocationId());
        entity.setStockBatchId(dto.getStockBatchId());
        entity.setAllocationBase(dto.getAllocationBase());
        entity.setQuantity(dto.getQuantity());
        entity.setAllocatedAmount(dto.getAllocatedAmount());
        entity.setAllocatedBaseAmount(dto.getAllocatedBaseAmount());
        entity.setUnitCostIncrease(dto.getUnitCostIncrease());
        return entity;
    }

    public void updateEntity(LandedCostAllocationLine entity, LandedCostAllocationLineUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setAllocationDocumentId(dto.getAllocationDocumentId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setStorageLocationId(dto.getStorageLocationId());
        entity.setStockBatchId(dto.getStockBatchId());
        entity.setAllocationBase(dto.getAllocationBase());
        entity.setQuantity(dto.getQuantity());
        entity.setAllocatedAmount(dto.getAllocatedAmount());
        entity.setAllocatedBaseAmount(dto.getAllocatedBaseAmount());
        entity.setUnitCostIncrease(dto.getUnitCostIncrease());
    }
}