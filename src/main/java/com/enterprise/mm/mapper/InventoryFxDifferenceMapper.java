package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.InventoryFxDifferenceResponse;
import com.enterprise.mm.payload.InventoryFxDifferenceUpdateRequest;
import com.enterprise.mm.payload.InventoryFxDifferenceCreateRequest;
import com.enterprise.mm.entity.InventoryFxDifference;
import org.springframework.stereotype.Component;

@Component
public class InventoryFxDifferenceMapper {
    public InventoryFxDifferenceResponse toResponse(InventoryFxDifference entity) {
        if (entity == null) {
            return null;
        }
        InventoryFxDifferenceResponse dto = new InventoryFxDifferenceResponse();
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
        dto.setPeriodStart(entity.getPeriodStart());
        dto.setPeriodEnd(entity.getPeriodEnd());
        dto.setDocumentDate(entity.getDocumentDate());
        dto.setReferenceType(entity.getReferenceType());
        dto.setReferenceId(entity.getReferenceId());
        dto.setMaterialId(entity.getMaterialId());
        dto.setWarehouseId(entity.getWarehouseId());
        dto.setSupplierId(entity.getSupplierId());
        dto.setDocumentCurrencyCode(entity.getDocumentCurrencyCode());
        dto.setBaseCurrencyCode(entity.getBaseCurrencyCode());
        dto.setForeignAmount(entity.getForeignAmount());
        dto.setDocumentRate(entity.getDocumentRate());
        dto.setClosingRate(entity.getClosingRate());
        dto.setBaseAmount(entity.getBaseAmount());
        dto.setRevaluedBaseAmount(entity.getRevaluedBaseAmount());
        dto.setDifferenceAmount(entity.getDifferenceAmount());
        dto.setDifferenceType(entity.getDifferenceType());
        dto.setPostedAt(entity.getPostedAt());
        return dto;
    }

    public InventoryFxDifference toEntity(InventoryFxDifferenceCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        InventoryFxDifference entity = new InventoryFxDifference();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setPeriodStart(dto.getPeriodStart());
        entity.setPeriodEnd(dto.getPeriodEnd());
        entity.setDocumentDate(dto.getDocumentDate());
        entity.setReferenceType(dto.getReferenceType());
        entity.setReferenceId(dto.getReferenceId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setSupplierId(dto.getSupplierId());
        entity.setDocumentCurrencyCode(dto.getDocumentCurrencyCode());
        entity.setBaseCurrencyCode(dto.getBaseCurrencyCode());
        entity.setForeignAmount(dto.getForeignAmount());
        entity.setDocumentRate(dto.getDocumentRate());
        entity.setClosingRate(dto.getClosingRate());
        entity.setBaseAmount(dto.getBaseAmount());
        entity.setRevaluedBaseAmount(dto.getRevaluedBaseAmount());
        entity.setDifferenceAmount(dto.getDifferenceAmount());
        entity.setDifferenceType(dto.getDifferenceType());
        entity.setPostedAt(dto.getPostedAt());
        return entity;
    }

    public void updateEntity(InventoryFxDifference entity, InventoryFxDifferenceUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setPeriodStart(dto.getPeriodStart());
        entity.setPeriodEnd(dto.getPeriodEnd());
        entity.setDocumentDate(dto.getDocumentDate());
        entity.setReferenceType(dto.getReferenceType());
        entity.setReferenceId(dto.getReferenceId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setSupplierId(dto.getSupplierId());
        entity.setDocumentCurrencyCode(dto.getDocumentCurrencyCode());
        entity.setBaseCurrencyCode(dto.getBaseCurrencyCode());
        entity.setForeignAmount(dto.getForeignAmount());
        entity.setDocumentRate(dto.getDocumentRate());
        entity.setClosingRate(dto.getClosingRate());
    }
}
