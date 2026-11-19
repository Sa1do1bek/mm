package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.InventoryAccountingReconciliationResponse;
import com.enterprise.mm.payload.InventoryAccountingReconciliationUpdateRequest;
import com.enterprise.mm.payload.InventoryAccountingReconciliationCreateRequest;
import com.enterprise.mm.entity.InventoryAccountingReconciliation;
import org.springframework.stereotype.Component;

@Component
public class InventoryAccountingReconciliationMapper {
    public InventoryAccountingReconciliationResponse toResponse(InventoryAccountingReconciliation entity) {
        if (entity == null) {
            return null;
        }
        InventoryAccountingReconciliationResponse dto = new InventoryAccountingReconciliationResponse();
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
        dto.setWarehouseId(entity.getWarehouseId());
        dto.setAccountingSystem(entity.getAccountingSystem());
        dto.setExternalReference(entity.getExternalReference());
        dto.setInventoryValue(entity.getInventoryValue());
        dto.setTransitStockValue(entity.getTransitStockValue());
        dto.setPhysicalStockValue(java.util.Objects.requireNonNullElse(entity.getInventoryValue(), java.math.BigDecimal.ZERO)
            .subtract(java.util.Objects.requireNonNullElse(entity.getTransitStockValue(), java.math.BigDecimal.ZERO)));
        dto.setAccountingValue(entity.getAccountingValue());
        dto.setDifferenceAmount(entity.getDifferenceAmount());
        dto.setToleranceAmount(entity.getToleranceAmount());
        dto.setDifferenceStatus(entity.getDifferenceStatus());
        dto.setReconciledAt(entity.getReconciledAt());
        dto.setPostedAt(entity.getPostedAt());
        return dto;
    }

    public InventoryAccountingReconciliation toEntity(InventoryAccountingReconciliationCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        InventoryAccountingReconciliation entity = new InventoryAccountingReconciliation();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setPeriodStart(dto.getPeriodStart());
        entity.setPeriodEnd(dto.getPeriodEnd());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setAccountingSystem(dto.getAccountingSystem());
        entity.setExternalReference(dto.getExternalReference());
        entity.setInventoryValue(dto.getInventoryValue());
        entity.setAccountingValue(dto.getAccountingValue());
        entity.setDifferenceAmount(dto.getDifferenceAmount());
        entity.setToleranceAmount(dto.getToleranceAmount());
        entity.setDifferenceStatus(dto.getDifferenceStatus());
        entity.setReconciledAt(dto.getReconciledAt());
        entity.setPostedAt(dto.getPostedAt());
        return entity;
    }

    public void updateEntity(InventoryAccountingReconciliation entity, InventoryAccountingReconciliationUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setPeriodStart(dto.getPeriodStart());
        entity.setPeriodEnd(dto.getPeriodEnd());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setAccountingSystem(dto.getAccountingSystem());
        entity.setExternalReference(dto.getExternalReference());
        entity.setAccountingValue(dto.getAccountingValue());
        entity.setToleranceAmount(dto.getToleranceAmount());
    }
}
