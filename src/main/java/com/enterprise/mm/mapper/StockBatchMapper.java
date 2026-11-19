package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.StockBatchResponse;
import com.enterprise.mm.payload.StockBatchUpdateRequest;
import com.enterprise.mm.payload.StockBatchCreateRequest;
import com.enterprise.mm.entity.StockBatch;
import org.springframework.stereotype.Component;

@Component
public class StockBatchMapper {
    public StockBatchResponse toResponse(StockBatch entity) {
        if (entity == null) {
            return null;
        }
        StockBatchResponse dto = new StockBatchResponse();
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
        dto.setMaterialCode(BaseMapper.materialCode(entity.getMaterial()));
        dto.setMaterialName(BaseMapper.materialName(entity.getMaterial()));
        dto.setWarehouseId(entity.getWarehouseId());
        dto.setWarehouseCode(BaseMapper.code(entity.getWarehouse()));
        dto.setWarehouseName(BaseMapper.name(entity.getWarehouse()));
        dto.setBatchNumber(entity.getBatchNumber());
        dto.setSerialNumber(entity.getSerialNumber());
        dto.setBarcode(entity.getBarcode());
        dto.setQrCode(entity.getQrCode());
        dto.setRfidTag(entity.getRfidTag());
        dto.setQuantity(entity.getQuantity());
        dto.setReceivedDate(entity.getReceivedDate());
        dto.setExpirationDate(entity.getExpirationDate());
        dto.setUnitCost(entity.getUnitCost());
        dto.setPurchaseUnitCost(entity.getPurchaseUnitCost());
        dto.setAdditionalCostPerUnit(entity.getAdditionalCostPerUnit());
        dto.setFinalUnitCost(entity.getFinalUnitCost());
        return dto;
    }

    public StockBatch toEntity(StockBatchCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        StockBatch entity = new StockBatch();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setBatchNumber(dto.getBatchNumber());
        entity.setSerialNumber(dto.getSerialNumber());
        entity.setBarcode(dto.getBarcode());
        entity.setQrCode(dto.getQrCode());
        entity.setRfidTag(dto.getRfidTag());
        entity.setQuantity(dto.getQuantity());
        entity.setReceivedDate(dto.getReceivedDate());
        entity.setExpirationDate(dto.getExpirationDate());
        entity.setUnitCost(dto.getUnitCost());
        entity.setPurchaseUnitCost(dto.getUnitCost() == null ? java.math.BigDecimal.ZERO : dto.getUnitCost());
        entity.setFinalUnitCost(dto.getUnitCost() == null ? java.math.BigDecimal.ZERO : dto.getUnitCost());
        return entity;
    }

    public void updateEntity(StockBatch entity, StockBatchUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setBatchNumber(dto.getBatchNumber());
        entity.setSerialNumber(dto.getSerialNumber());
        entity.setBarcode(dto.getBarcode());
        entity.setQrCode(dto.getQrCode());
        entity.setRfidTag(dto.getRfidTag());
        entity.setQuantity(dto.getQuantity());
        entity.setReceivedDate(dto.getReceivedDate());
        entity.setExpirationDate(dto.getExpirationDate());
        entity.setUnitCost(dto.getUnitCost());
    }
}
