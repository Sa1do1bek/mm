package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.ConsignmentStockTransactionResponse;
import com.enterprise.mm.payload.ConsignmentStockTransactionUpdateRequest;
import com.enterprise.mm.payload.ConsignmentStockTransactionCreateRequest;
import com.enterprise.mm.entity.ConsignmentStockTransaction;
import org.springframework.stereotype.Component;

@Component
public class ConsignmentStockTransactionMapper {
    public ConsignmentStockTransactionResponse toResponse(ConsignmentStockTransaction entity) {
        if (entity == null) {
            return null;
        }
        ConsignmentStockTransactionResponse dto = new ConsignmentStockTransactionResponse();
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
        dto.setVmiAgreementId(entity.getVmiAgreementId());
        dto.setSupplierId(entity.getSupplierId());
        dto.setMaterialId(entity.getMaterialId());
        dto.setWarehouseId(entity.getWarehouseId());
        dto.setStorageLocationId(entity.getStorageLocationId());
        dto.setStockMovementId(entity.getStockMovementId());
        dto.setTransactionType(entity.getTransactionType());
        dto.setQuantity(entity.getQuantity());
        dto.setUnitCost(entity.getUnitCost());
        dto.setAmount(entity.getAmount());
        dto.setSettlementStatus(entity.getSettlementStatus());
        dto.setSettlementReference(entity.getSettlementReference());
        dto.setTransactionAt(entity.getTransactionAt());
        return dto;
    }

    public ConsignmentStockTransaction toEntity(ConsignmentStockTransactionCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        ConsignmentStockTransaction entity = new ConsignmentStockTransaction();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setVmiAgreementId(dto.getVmiAgreementId());
        entity.setSupplierId(dto.getSupplierId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setStorageLocationId(dto.getStorageLocationId());
        entity.setStockMovementId(dto.getStockMovementId());
        entity.setTransactionType(dto.getTransactionType());
        entity.setQuantity(dto.getQuantity());
        entity.setUnitCost(dto.getUnitCost());
        entity.setAmount(dto.getAmount());
        entity.setSettlementStatus(dto.getSettlementStatus());
        entity.setSettlementReference(dto.getSettlementReference());
        entity.setTransactionAt(dto.getTransactionAt());
        return entity;
    }

    public void updateEntity(ConsignmentStockTransaction entity, ConsignmentStockTransactionUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setVmiAgreementId(dto.getVmiAgreementId());
        entity.setSupplierId(dto.getSupplierId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setStorageLocationId(dto.getStorageLocationId());
        entity.setStockMovementId(dto.getStockMovementId());
        entity.setTransactionType(dto.getTransactionType());
        entity.setQuantity(dto.getQuantity());
        entity.setUnitCost(dto.getUnitCost());
        entity.setAmount(dto.getAmount());
        entity.setSettlementStatus(dto.getSettlementStatus());
        entity.setSettlementReference(dto.getSettlementReference());
        entity.setTransactionAt(dto.getTransactionAt());
    }
}