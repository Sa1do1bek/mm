package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.AdvanceShippingNoticeItemResponse;
import com.enterprise.mm.payload.AdvanceShippingNoticeItemUpdateRequest;
import com.enterprise.mm.payload.AdvanceShippingNoticeItemCreateRequest;
import com.enterprise.mm.entity.AdvanceShippingNoticeItem;
import org.springframework.stereotype.Component;

@Component
public class AdvanceShippingNoticeItemMapper {
    public AdvanceShippingNoticeItemResponse toResponse(AdvanceShippingNoticeItem entity) {
        if (entity == null) {
            return null;
        }
        AdvanceShippingNoticeItemResponse dto = new AdvanceShippingNoticeItemResponse();
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
        dto.setAsnId(entity.getAsnId());
        dto.setAsnCode(BaseMapper.code(entity.getAsn()));
        dto.setAsnName(BaseMapper.name(entity.getAsn()));
        dto.setPurchaseOrderItemId(entity.getPurchaseOrderItemId());
        dto.setPurchaseOrderItemCode(BaseMapper.code(entity.getPurchaseOrderItem()));
        dto.setPurchaseOrderItemName(BaseMapper.name(entity.getPurchaseOrderItem()));
        dto.setMaterialId(entity.getMaterialId());
        dto.setMaterialCode(BaseMapper.materialCode(entity.getMaterial()));
        dto.setMaterialName(BaseMapper.materialName(entity.getMaterial()));
        dto.setQuantity(entity.getQuantity());
        dto.setReceivedQuantity(entity.getReceivedQuantity());
        dto.setCancelledQuantity(entity.getCancelledQuantity());
        dto.setRemainingQuantity(nvl(entity.getQuantity())
            .subtract(nvl(entity.getReceivedQuantity()))
            .subtract(nvl(entity.getCancelledQuantity()))
            .max(java.math.BigDecimal.ZERO));
        dto.setPackageCount(entity.getPackageCount());
        dto.setPackageType(entity.getPackageType());
        dto.setGrossWeight(entity.getGrossWeight());
        dto.setNetWeight(entity.getNetWeight());
        dto.setBatchNumber(entity.getBatchNumber());
        dto.setSerialNumbers(entity.getSerialNumbers());
        dto.setExpirationDate(entity.getExpirationDate());
        return dto;
    }

    public AdvanceShippingNoticeItem toEntity(AdvanceShippingNoticeItemCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        AdvanceShippingNoticeItem entity = new AdvanceShippingNoticeItem();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setAsnId(dto.getAsnId());
        entity.setPurchaseOrderItemId(dto.getPurchaseOrderItemId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setQuantity(dto.getQuantity());
        entity.setPackageCount(dto.getPackageCount());
        entity.setPackageType(dto.getPackageType());
        entity.setGrossWeight(dto.getGrossWeight());
        entity.setNetWeight(dto.getNetWeight());
        entity.setBatchNumber(dto.getBatchNumber());
        entity.setSerialNumbers(dto.getSerialNumbers());
        entity.setExpirationDate(dto.getExpirationDate());
        return entity;
    }

    public void updateEntity(AdvanceShippingNoticeItem entity, AdvanceShippingNoticeItemUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setAsnId(dto.getAsnId());
        entity.setPurchaseOrderItemId(dto.getPurchaseOrderItemId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setQuantity(dto.getQuantity());
        entity.setPackageCount(dto.getPackageCount());
        entity.setPackageType(dto.getPackageType());
        entity.setGrossWeight(dto.getGrossWeight());
        entity.setNetWeight(dto.getNetWeight());
        entity.setBatchNumber(dto.getBatchNumber());
        entity.setSerialNumbers(dto.getSerialNumbers());
        entity.setExpirationDate(dto.getExpirationDate());
    }

    private java.math.BigDecimal nvl(java.math.BigDecimal value) {
        return value == null ? java.math.BigDecimal.ZERO : value;
    }
}
