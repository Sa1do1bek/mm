package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.CrossDockingTaskResponse;
import com.enterprise.mm.payload.CrossDockingTaskUpdateRequest;
import com.enterprise.mm.payload.CrossDockingTaskCreateRequest;
import com.enterprise.mm.entity.CrossDockingTask;
import org.springframework.stereotype.Component;

@Component
public class CrossDockingTaskMapper {
    public CrossDockingTaskResponse toResponse(CrossDockingTask entity) {
        if (entity == null) {
            return null;
        }
        CrossDockingTaskResponse dto = new CrossDockingTaskResponse();
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
        dto.setTaskNumber(entity.getTaskNumber());
        dto.setSupplierId(entity.getSupplierId());
        dto.setPurchaseOrderId(entity.getPurchaseOrderId());
        dto.setInboundStockMovementId(entity.getInboundStockMovementId());
        dto.setOutboundReferenceType(entity.getOutboundReferenceType());
        dto.setOutboundReferenceId(entity.getOutboundReferenceId());
        dto.setMaterialId(entity.getMaterialId());
        dto.setStockBatchId(entity.getStockBatchId());
        dto.setWarehouseId(entity.getWarehouseId());
        dto.setStagingLocationId(entity.getStagingLocationId());
        dto.setDestinationWarehouseId(entity.getDestinationWarehouseId());
        dto.setDestinationStorageLocationId(entity.getDestinationStorageLocationId());
        dto.setQuantity(entity.getQuantity());
        dto.setUnitCost(entity.getUnitCost());
        dto.setOutboundStockMovementId(entity.getOutboundStockMovementId());
        dto.setPlannedShipAt(entity.getPlannedShipAt());
        dto.setStartedAt(entity.getStartedAt());
        dto.setCompletedAt(entity.getCompletedAt());
        return dto;
    }

    public CrossDockingTask toEntity(CrossDockingTaskCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        CrossDockingTask entity = new CrossDockingTask();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setTaskNumber(dto.getTaskNumber());
        entity.setSupplierId(dto.getSupplierId());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setInboundStockMovementId(dto.getInboundStockMovementId());
        entity.setOutboundReferenceType(dto.getOutboundReferenceType());
        entity.setOutboundReferenceId(dto.getOutboundReferenceId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setStockBatchId(dto.getStockBatchId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setStagingLocationId(dto.getStagingLocationId());
        entity.setDestinationWarehouseId(dto.getDestinationWarehouseId());
        entity.setDestinationStorageLocationId(dto.getDestinationStorageLocationId());
        entity.setQuantity(dto.getQuantity());
        entity.setUnitCost(dto.getUnitCost());
        entity.setOutboundStockMovementId(dto.getOutboundStockMovementId());
        entity.setPlannedShipAt(dto.getPlannedShipAt());
        entity.setStartedAt(dto.getStartedAt());
        entity.setCompletedAt(dto.getCompletedAt());
        return entity;
    }

    public void updateEntity(CrossDockingTask entity, CrossDockingTaskUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setTaskNumber(dto.getTaskNumber());
        entity.setSupplierId(dto.getSupplierId());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setInboundStockMovementId(dto.getInboundStockMovementId());
        entity.setOutboundReferenceType(dto.getOutboundReferenceType());
        entity.setOutboundReferenceId(dto.getOutboundReferenceId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setStockBatchId(dto.getStockBatchId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setStagingLocationId(dto.getStagingLocationId());
        entity.setDestinationWarehouseId(dto.getDestinationWarehouseId());
        entity.setDestinationStorageLocationId(dto.getDestinationStorageLocationId());
        entity.setQuantity(dto.getQuantity());
        entity.setUnitCost(dto.getUnitCost());
        entity.setOutboundStockMovementId(dto.getOutboundStockMovementId());
        entity.setPlannedShipAt(dto.getPlannedShipAt());
        entity.setStartedAt(dto.getStartedAt());
        entity.setCompletedAt(dto.getCompletedAt());
    }
}