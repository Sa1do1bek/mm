package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.LandedCostAllocationResponse;
import com.enterprise.mm.payload.LandedCostAllocationUpdateRequest;
import com.enterprise.mm.payload.LandedCostAllocationCreateRequest;
import com.enterprise.mm.entity.LandedCostAllocation;
import org.springframework.stereotype.Component;

@Component
public class LandedCostAllocationMapper {
    public LandedCostAllocationResponse toResponse(LandedCostAllocation entity) {
        if (entity == null) {
            return null;
        }
        LandedCostAllocationResponse dto = new LandedCostAllocationResponse();
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
        dto.setAllocationMethod(entity.getAllocationMethod());
        dto.setDocumentDate(entity.getDocumentDate());
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setBaseCurrencyCode(entity.getBaseCurrencyCode());
        dto.setExchangeRate(entity.getExchangeRate());
        dto.setTotalAmount(entity.getTotalAmount());
        dto.setBaseTotalAmount(entity.getBaseTotalAmount());
        dto.setPostedAt(entity.getPostedAt());
        return dto;
    }

    public LandedCostAllocation toEntity(LandedCostAllocationCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        LandedCostAllocation entity = new LandedCostAllocation();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setImportDeliveryId(dto.getImportDeliveryId());
        entity.setAllocationMethod(dto.getAllocationMethod());
        entity.setDocumentDate(dto.getDocumentDate());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setBaseCurrencyCode(dto.getBaseCurrencyCode());
        entity.setExchangeRate(dto.getExchangeRate());
        entity.setTotalAmount(dto.getTotalAmount());
        entity.setBaseTotalAmount(dto.getBaseTotalAmount());
        entity.setPostedAt(dto.getPostedAt());
        return entity;
    }

    public void updateEntity(LandedCostAllocation entity, LandedCostAllocationUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setImportDeliveryId(dto.getImportDeliveryId());
        entity.setAllocationMethod(dto.getAllocationMethod());
        entity.setDocumentDate(dto.getDocumentDate());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setBaseCurrencyCode(dto.getBaseCurrencyCode());
        entity.setExchangeRate(dto.getExchangeRate());
        entity.setTotalAmount(dto.getTotalAmount());
    }
}
