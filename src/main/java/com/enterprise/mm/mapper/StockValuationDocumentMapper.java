package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.StockValuationDocumentResponse;
import com.enterprise.mm.payload.StockValuationDocumentUpdateRequest;
import com.enterprise.mm.payload.StockValuationDocumentCreateRequest;
import com.enterprise.mm.entity.StockValuationDocument;
import org.springframework.stereotype.Component;

@Component
public class StockValuationDocumentMapper {
    public StockValuationDocumentResponse toResponse(StockValuationDocument entity) {
        if (entity == null) {
            return null;
        }
        StockValuationDocumentResponse dto = new StockValuationDocumentResponse();
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
        dto.setDocumentType(entity.getDocumentType());
        dto.setValuationMethod(entity.getValuationMethod());
        dto.setDocumentDate(entity.getDocumentDate());
        dto.setQuantity(entity.getQuantity());
        dto.setOldUnitCost(entity.getOldUnitCost());
        dto.setNewUnitCost(entity.getNewUnitCost());
        dto.setAmount(entity.getAmount());
        dto.setReserveAmount(entity.getReserveAmount());
        dto.setReason(entity.getReason());
        dto.setPostedAt(entity.getPostedAt());
        return dto;
    }

    public StockValuationDocument toEntity(StockValuationDocumentCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        StockValuationDocument entity = new StockValuationDocument();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setDocumentType(dto.getDocumentType());
        entity.setValuationMethod(dto.getValuationMethod());
        entity.setDocumentDate(dto.getDocumentDate());
        entity.setQuantity(dto.getQuantity());
        entity.setOldUnitCost(dto.getOldUnitCost());
        entity.setNewUnitCost(dto.getNewUnitCost());
        entity.setAmount(dto.getAmount());
        entity.setReserveAmount(dto.getReserveAmount());
        entity.setReason(dto.getReason());
        entity.setPostedAt(dto.getPostedAt());
        return entity;
    }

    public void updateEntity(StockValuationDocument entity, StockValuationDocumentUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setDocumentType(dto.getDocumentType());
        entity.setValuationMethod(dto.getValuationMethod());
        entity.setDocumentDate(dto.getDocumentDate());
        entity.setQuantity(dto.getQuantity());
        entity.setOldUnitCost(dto.getOldUnitCost());
        entity.setNewUnitCost(dto.getNewUnitCost());
        entity.setAmount(dto.getAmount());
        entity.setReserveAmount(dto.getReserveAmount());
        entity.setReason(dto.getReason());
    }
}
