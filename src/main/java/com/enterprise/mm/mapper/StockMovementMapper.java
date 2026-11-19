package com.enterprise.mm.mapper;

import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.payload.StockMovementResponse;
import com.enterprise.mm.payload.StockMovementUpdateRequest;
import com.enterprise.mm.payload.StockMovementCreateRequest;
import com.enterprise.mm.payload.StockMovementDocumentItemRequest;
import com.enterprise.mm.entity.StockMovement;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class StockMovementMapper {
    public StockMovementResponse toResponse(StockMovement entity) {
        if (entity == null) {
            return null;
        }
        StockMovementResponse dto = new StockMovementResponse();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setCompanyId(entity.getCompanyId());
        dto.setCode(entity.getCode());
        dto.setName(entity.getName());
        dto.setStatus(entity.getStatus());
        dto.setStockMovementStatus(parseEnum(StockMovementStatus.class, entity.getStatus()));
        dto.setDescription(entity.getDescription());
        dto.setDeleted(entity.isDeleted());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setUpdatedBy(entity.getUpdatedBy());
        dto.setVersion(entity.getVersion());
        dto.setDocumentId(entity.getDocumentId());
        dto.setMaterialId(entity.getMaterialId());
        dto.setUnitOfMeasureCode(entity.getMaterial() == null || entity.getMaterial().getUnitOfMeasure() == null
            ? null
            : entity.getMaterial().getUnitOfMeasure().getCode());
        dto.setUnitOfMeasureShortName(entity.getMaterial() == null || entity.getMaterial().getUnitOfMeasure() == null
            ? null
            : com.enterprise.mm.common.UnitOfMeasureLabels.localizedShort(entity.getMaterial().getUnitOfMeasure(), org.springframework.context.i18n.LocaleContextHolder.getLocale()));
        dto.setMaterialCode(BaseMapper.materialCode(entity.getMaterial()));
        dto.setMaterialName(BaseMapper.materialName(entity.getMaterial()));
        dto.setWarehouseId(entity.getWarehouseId());
        dto.setWarehouseCode(BaseMapper.code(entity.getWarehouse()));
        dto.setWarehouseName(BaseMapper.name(entity.getWarehouse()));
        dto.setTargetWarehouseId(entity.getTargetWarehouseId());
        dto.setTargetWarehouseCode(BaseMapper.code(entity.getTargetWarehouse()));
        dto.setTargetWarehouseName(BaseMapper.name(entity.getTargetWarehouse()));
        dto.setStorageLocationId(entity.getStorageLocationId());
        dto.setStorageLocationCode(BaseMapper.code(entity.getStorageLocation()));
        dto.setStorageLocationName(BaseMapper.name(entity.getStorageLocation()));
        dto.setStorageBinId(entity.getStorageBinId());
        dto.setStorageBinCode(BaseMapper.storageBinCode(entity.getStorageBin()));
        dto.setStorageBinName(BaseMapper.storageBinName(entity.getStorageBin()));
        dto.setTargetStorageLocationId(entity.getTargetStorageLocationId());
        dto.setTargetStorageLocationCode(BaseMapper.code(entity.getTargetStorageLocation()));
        dto.setTargetStorageLocationName(BaseMapper.name(entity.getTargetStorageLocation()));
        dto.setTargetStorageBinId(entity.getTargetStorageBinId());
        dto.setTargetStorageBinCode(BaseMapper.storageBinCode(entity.getTargetStorageBin()));
        dto.setTargetStorageBinName(BaseMapper.storageBinName(entity.getTargetStorageBin()));
        dto.setStockBatchId(entity.getStockBatchId());
        dto.setStockBatchCode(BaseMapper.stockBatchCode(entity.getStockBatch()));
        dto.setStockBatchName(BaseMapper.stockBatchName(entity.getStockBatch()));
        dto.setBatchNumber(entity.getBatchNumber());
        dto.setMovementType(entity.getMovementType());
        dto.setQuantity(entity.getQuantity());
        dto.setUnitCost(entity.getUnitCost());
        dto.setTotalCost(entity.getTotalCost());
        dto.setAccountCodeSnapshot(entity.getAccountCodeSnapshot());
        dto.setMovementDate(entity.getMovementDate() != null ? entity.getMovementDate() : (entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDate() : null));
        dto.setRequestDate(entity.getRequestDate());
        dto.setPostedAt(entity.getPostedAt());
        dto.setActorName(preferActor(entity.getUpdatedBy(), entity.getCreatedBy()));
        dto.setReferenceType(entity.getReferenceType());
        dto.setReferenceId(entity.getReferenceId());
        dto.setContractSpecificationId(entity.getContractSpecificationId());
        dto.setReversalOfId(entity.getReversalOfId());
        dto.setReversalOfCode(BaseMapper.code(entity.getReversalOf()));
        dto.setReversalOfName(BaseMapper.name(entity.getReversalOf()));
        return dto;
    }

    public StockMovement toEntity(StockMovementCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        StockMovementDocumentItemRequest singleItem = dto.getItems() != null && dto.getItems().size() == 1
            ? dto.getItems().getFirst() : null;
        StockMovement entity = new StockMovement();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(enumName(dto.getStatus()));
        entity.setDescription(dto.getDescription());
        entity.setMaterialId(dto.getMaterialId() != null ? dto.getMaterialId() : singleItem == null ? null : singleItem.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setTargetWarehouseId(dto.getTargetWarehouseId());
        entity.setStorageLocationId(dto.getStorageLocationId() != null ? dto.getStorageLocationId() : singleItem == null ? null : singleItem.getStorageLocationId());
        entity.setStorageBinId(dto.getStorageBinId() != null ? dto.getStorageBinId() : singleItem == null ? null : singleItem.getStorageBinId());
        entity.setTargetStorageLocationId(dto.getTargetStorageLocationId() != null ? dto.getTargetStorageLocationId() : singleItem == null ? null : singleItem.getTargetStorageLocationId());
        entity.setTargetStorageBinId(dto.getTargetStorageBinId());
        entity.setStockBatchId(dto.getStockBatchId() != null ? dto.getStockBatchId() : singleItem == null ? null : singleItem.getStockBatchId());
        entity.setBatchNumber(dto.getBatchNumber());
        entity.setMovementType(dto.getMovementType());
        entity.setQuantity(dto.getQuantity() != null ? dto.getQuantity() : singleItem == null ? null : singleItem.getQuantity());
        entity.setUnitCost(dto.getUnitCost() != null ? dto.getUnitCost() : singleItem == null ? null : singleItem.getUnitCost());
        entity.setTotalCost(dto.getTotalCost());
        entity.setMovementDate(dto.getMovementDate());
        entity.setRequestDate(dto.getRequestDate());
        entity.setPostedAt(dto.getPostedAt());
        entity.setReferenceType(dto.getReferenceType());
        entity.setReferenceId(dto.getReferenceId());
        entity.setReversalOfId(dto.getReversalOfId());
        return entity;
    }

    public void updateEntity(StockMovement entity, StockMovementUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(enumName(dto.getStatus()));
        entity.setDescription(dto.getDescription());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setTargetWarehouseId(dto.getTargetWarehouseId());
        entity.setStorageLocationId(dto.getStorageLocationId());
        entity.setStorageBinId(dto.getStorageBinId());
        entity.setTargetStorageLocationId(dto.getTargetStorageLocationId());
        entity.setTargetStorageBinId(dto.getTargetStorageBinId());
        entity.setStockBatchId(dto.getStockBatchId());
        entity.setBatchNumber(dto.getBatchNumber());
        entity.setMovementType(dto.getMovementType());
        entity.setQuantity(dto.getQuantity());
        entity.setUnitCost(dto.getUnitCost());
        entity.setTotalCost(dto.getTotalCost());
        entity.setMovementDate(dto.getMovementDate());
        entity.setRequestDate(dto.getRequestDate());
        entity.setPostedAt(dto.getPostedAt());
        entity.setReferenceType(dto.getReferenceType());
        entity.setReferenceId(dto.getReferenceId());
        entity.setReversalOfId(dto.getReversalOfId());
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private <T extends Enum<T>> T parseEnum(Class<T> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String preferActor(String updatedBy, String createdBy) {
        if (updatedBy != null && !updatedBy.isBlank()) {
            return updatedBy;
        }
        return createdBy;
    }
}
