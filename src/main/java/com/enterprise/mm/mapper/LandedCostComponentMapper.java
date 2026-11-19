package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.LandedCostComponentResponse;
import com.enterprise.mm.payload.LandedCostComponentUpdateRequest;
import com.enterprise.mm.payload.LandedCostComponentCreateRequest;
import com.enterprise.mm.entity.LandedCostComponent;
import org.springframework.stereotype.Component;

@Component
public class LandedCostComponentMapper {
    public LandedCostComponentResponse toResponse(LandedCostComponent entity) {
        if (entity == null) {
            return null;
        }
        LandedCostComponentResponse dto = new LandedCostComponentResponse();
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
        dto.setImportDeliveryId(entity.getImportDeliveryId());
        dto.setImportDeliveryCode(BaseMapper.code(entity.getImportDelivery()));
        dto.setImportDeliveryName(BaseMapper.name(entity.getImportDelivery()));
        dto.setComponentType(entity.getComponentType());
        dto.setAmount(entity.getAmount());
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setAllocationMethod(entity.getAllocationMethod());
        dto.setSupplierId(entity.getSupplierId());
        dto.setSupplierCode(BaseMapper.supplierCode(entity.getSupplier()));
        dto.setSupplierName(BaseMapper.supplierName(entity.getSupplier()));
        dto.setInvoiceNumber(entity.getInvoiceNumber());
        dto.setInvoiceDate(entity.getInvoiceDate());
        dto.setIncludedInInventoryCost(entity.isIncludedInInventoryCost());
        dto.setPostedAt(entity.getPostedAt());
        return dto;
    }

    public LandedCostComponent toEntity(LandedCostComponentCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        LandedCostComponent entity = new LandedCostComponent();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setImportDeliveryId(dto.getImportDeliveryId());
        entity.setComponentType(dto.getComponentType());
        entity.setAmount(dto.getAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setAllocationMethod(dto.getAllocationMethod());
        entity.setSupplierId(dto.getSupplierId());
        entity.setInvoiceNumber(dto.getInvoiceNumber());
        entity.setInvoiceDate(dto.getInvoiceDate());
        entity.setIncludedInInventoryCost(dto.isIncludedInInventoryCost());
        entity.setPostedAt(dto.getPostedAt());
        return entity;
    }

    public void updateEntity(LandedCostComponent entity, LandedCostComponentUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setImportDeliveryId(dto.getImportDeliveryId());
        entity.setComponentType(dto.getComponentType());
        entity.setAmount(dto.getAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setAllocationMethod(dto.getAllocationMethod());
        entity.setSupplierId(dto.getSupplierId());
        entity.setInvoiceNumber(dto.getInvoiceNumber());
        entity.setInvoiceDate(dto.getInvoiceDate());
        entity.setIncludedInInventoryCost(dto.isIncludedInInventoryCost());
    }
}
