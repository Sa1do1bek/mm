package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.StockValuationResponse;
import com.enterprise.mm.payload.StockValuationUpdateRequest;
import com.enterprise.mm.payload.StockValuationCreateRequest;
import com.enterprise.mm.entity.StockValuation;
import org.springframework.stereotype.Component;

@Component
public class StockValuationMapper {
    public StockValuationResponse toResponse(StockValuation entity) {
        if (entity == null) {
            return null;
        }
        StockValuationResponse dto = new StockValuationResponse();
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
        dto.setWarehouseId(entity.getWarehouseId());
        dto.setValuationMethod(entity.getValuationMethod());
        dto.setQuantity(entity.getQuantity());
        dto.setUnitCost(entity.getUnitCost());
        dto.setTotalValue(entity.getTotalValue());
        dto.setImpairmentReserve(entity.getImpairmentReserve());
        dto.setRevaluationAmount(entity.getRevaluationAmount());
        dto.setCarryingValue(entity.getCarryingValue());
        dto.setValuationBasis(entity.getValuationBasis());
        dto.setCalculatedAt(entity.getCalculatedAt());
        return dto;
    }

    public StockValuation toEntity(StockValuationCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        StockValuation entity = new StockValuation();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setValuationMethod(dto.getValuationMethod());
        entity.setQuantity(dto.getQuantity());
        entity.setUnitCost(dto.getUnitCost());
        entity.setTotalValue(dto.getTotalValue());
        entity.setImpairmentReserve(dto.getImpairmentReserve());
        entity.setRevaluationAmount(dto.getRevaluationAmount());
        entity.setCarryingValue(dto.getCarryingValue());
        entity.setValuationBasis(dto.getValuationBasis());
        entity.setCalculatedAt(dto.getCalculatedAt());
        return entity;
    }

    public void updateEntity(StockValuation entity, StockValuationUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setValuationMethod(dto.getValuationMethod());
        entity.setQuantity(dto.getQuantity());
        entity.setUnitCost(dto.getUnitCost());
        entity.setTotalValue(dto.getTotalValue());
        entity.setImpairmentReserve(dto.getImpairmentReserve());
        entity.setRevaluationAmount(dto.getRevaluationAmount());
        entity.setCarryingValue(dto.getCarryingValue());
        entity.setValuationBasis(dto.getValuationBasis());
        entity.setCalculatedAt(dto.getCalculatedAt());
    }
}