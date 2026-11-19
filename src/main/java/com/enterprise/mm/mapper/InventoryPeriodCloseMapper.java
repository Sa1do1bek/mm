package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.InventoryPeriodCloseResponse;
import com.enterprise.mm.payload.InventoryPeriodCloseUpdateRequest;
import com.enterprise.mm.payload.InventoryPeriodCloseCreateRequest;
import com.enterprise.mm.entity.InventoryPeriodClose;
import com.enterprise.mm.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InventoryPeriodCloseMapper {
    private final WarehouseRepository warehouseRepository;

    public InventoryPeriodCloseResponse toResponse(InventoryPeriodClose entity) {
        if (entity == null) {
            return null;
        }
        InventoryPeriodCloseResponse dto = new InventoryPeriodCloseResponse();
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
        if (entity.getWarehouseId() != null) {
            warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                entity.getTenantId(), entity.getCompanyId(), entity.getWarehouseId()
            ).ifPresent(warehouse -> {
                dto.setWarehouseCode(warehouse.getCode());
                dto.setWarehouseName(warehouse.getName());
            });
        }
        dto.setTotalStockValue(entity.getTotalStockValue());
        dto.setTransitStockValue(entity.getTransitStockValue());
        dto.setPhysicalStockValue(java.util.Objects.requireNonNullElse(entity.getTotalStockValue(), java.math.BigDecimal.ZERO)
            .subtract(java.util.Objects.requireNonNullElse(entity.getTransitStockValue(), java.math.BigDecimal.ZERO)));
        dto.setLandedCostAmount(entity.getLandedCostAmount());
        dto.setFxDifferenceAmount(entity.getFxDifferenceAmount());
        dto.setMovementCount(entity.getMovementCount());
        dto.setOpenMovementCount(entity.getOpenMovementCount());
        dto.setValuationCount(entity.getValuationCount());
        dto.setAccountingReconciliationId(entity.getAccountingReconciliationId());
        dto.setAccountingReconciliationStatus(entity.getAccountingReconciliationStatus());
        dto.setAccountingDifferenceAmount(entity.getAccountingDifferenceAmount());
        dto.setClosedAt(entity.getClosedAt());
        return dto;
    }

    public InventoryPeriodClose toEntity(InventoryPeriodCloseCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        InventoryPeriodClose entity = new InventoryPeriodClose();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setPeriodStart(dto.getPeriodStart());
        entity.setPeriodEnd(dto.getPeriodEnd());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setTotalStockValue(dto.getTotalStockValue());
        entity.setLandedCostAmount(dto.getLandedCostAmount());
        entity.setFxDifferenceAmount(dto.getFxDifferenceAmount());
        entity.setMovementCount(dto.getMovementCount());
        entity.setOpenMovementCount(dto.getOpenMovementCount());
        entity.setValuationCount(dto.getValuationCount());
        entity.setAccountingReconciliationId(dto.getAccountingReconciliationId());
        entity.setAccountingReconciliationStatus(dto.getAccountingReconciliationStatus());
        entity.setAccountingDifferenceAmount(dto.getAccountingDifferenceAmount());
        entity.setClosedAt(dto.getClosedAt());
        return entity;
    }

    public void updateEntity(InventoryPeriodClose entity, InventoryPeriodCloseUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setPeriodStart(dto.getPeriodStart());
        entity.setPeriodEnd(dto.getPeriodEnd());
        entity.setWarehouseId(dto.getWarehouseId());
    }
}
