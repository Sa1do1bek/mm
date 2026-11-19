package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.MaterialMarketPrice;
import com.enterprise.mm.payload.MaterialMarketPriceCreateRequest;
import com.enterprise.mm.payload.MaterialMarketPriceResponse;
import com.enterprise.mm.payload.MaterialMarketPriceUpdateRequest;
import org.springframework.stereotype.Component;

@Component
public class MaterialMarketPriceMapper {
    public MaterialMarketPriceResponse toResponse(MaterialMarketPrice entity) {
        if (entity == null) {
            return null;
        }
        MaterialMarketPriceResponse dto = new MaterialMarketPriceResponse();
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
        dto.setMarketUnitPrice(entity.getMarketUnitPrice());
        dto.setNetRealizableUnitPrice(entity.getNetRealizableUnitPrice());
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setEffectiveDate(entity.getEffectiveDate());
        dto.setSourceType(entity.getSourceType());
        dto.setSourceReference(entity.getSourceReference());
        dto.setApprovedBy(entity.getApprovedBy());
        dto.setApprovedAt(entity.getApprovedAt());
        return dto;
    }

    public MaterialMarketPrice toEntity(MaterialMarketPriceCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        MaterialMarketPrice entity = new MaterialMarketPrice();
        updateCommon(entity, dto);
        return entity;
    }

    public void updateEntity(MaterialMarketPrice entity, MaterialMarketPriceUpdateRequest dto) {
        updateCommon(entity, dto);
    }

    private void updateCommon(MaterialMarketPrice entity, MaterialMarketPriceCreateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setMarketUnitPrice(dto.getMarketUnitPrice());
        entity.setNetRealizableUnitPrice(dto.getNetRealizableUnitPrice());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setEffectiveDate(dto.getEffectiveDate());
        entity.setSourceType(dto.getSourceType());
        entity.setSourceReference(dto.getSourceReference());
    }
}
