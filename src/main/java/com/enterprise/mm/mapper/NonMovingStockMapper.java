package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.Material;
import com.enterprise.mm.payload.NonMovingStockResponse;
import com.enterprise.mm.payload.NonMovingStockUpdateRequest;
import com.enterprise.mm.payload.NonMovingStockCreateRequest;
import com.enterprise.mm.entity.NonMovingStock;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class NonMovingStockMapper {
    private final MaterialRepository materialRepository;
    private final WarehouseRepository warehouseRepository;
    private final StockBalanceRepository stockBalanceRepository;

    public NonMovingStockMapper(
        MaterialRepository materialRepository,
        WarehouseRepository warehouseRepository,
        StockBalanceRepository stockBalanceRepository
    ) {
        this.materialRepository = materialRepository;
        this.warehouseRepository = warehouseRepository;
        this.stockBalanceRepository = stockBalanceRepository;
    }

    public NonMovingStockResponse toResponse(NonMovingStock entity) {
        if (entity == null) {
            return null;
        }
        Material material = material(entity.getMaterialId());
        NonMovingStockResponse dto = new NonMovingStockResponse();
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
        dto.setMaterialCode(material == null ? null : material.getCode());
        dto.setMaterialName(material == null ? null : material.getName());
        dto.setMaterialStatus(material == null ? null : material.getStatus());
        dto.setArticle(material == null ? null : material.getArticle());
        dto.setMaterialArticle(material == null ? null : material.getArticle());
        dto.setMaterialUnitOfMeasure(material == null || material.getUnitOfMeasure() == null
            ? null
            : (material.getUnitOfMeasure().getMeasurementNameShort() == null || material.getUnitOfMeasure().getMeasurementNameShort().isBlank()
                ? material.getUnitOfMeasure().getName()
                : material.getUnitOfMeasure().getMeasurementNameShort()));
        dto.setMaterialCountryOfOrigin(material == null ? null : material.getCountryOfOrigin());
        dto.setMaterialManufacturer(material == null ? null : material.getManufacturer());
        dto.setWarehouseId(entity.getWarehouseId());
        warehouseRepository.findByTenantIdAndIdAndIsDeletedFalse(entity.getTenantId(), entity.getWarehouseId())
            .filter(warehouse -> java.util.Objects.equals(entity.getCompanyId(), warehouse.getCompanyId()))
            .ifPresent(warehouse -> {
                dto.setWarehouseCode(warehouse.getCode());
                dto.setWarehouseName(warehouse.getName());
            });
        dto.setQuantity(entity.getQuantity());
        dto.setEstimatedValue(estimatedValue(entity));
        dto.setMonthsWithoutMovement(entity.getMonthsWithoutMovement());
        dto.setDetectedAt(entity.getDetectedAt());
        dto.setPlannedAction(entity.getPlannedAction());
        dto.setActionComment(entity.getActionComment());
        return dto;
    }

    public NonMovingStock toEntity(NonMovingStockCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        NonMovingStock entity = new NonMovingStock();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setQuantity(dto.getQuantity());
        entity.setMonthsWithoutMovement(dto.getMonthsWithoutMovement());
        entity.setDetectedAt(dto.getDetectedAt());
        entity.setPlannedAction(dto.getPlannedAction());
        entity.setActionComment(dto.getActionComment());
        return entity;
    }

    public void updateEntity(NonMovingStock entity, NonMovingStockUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setQuantity(dto.getQuantity());
        entity.setMonthsWithoutMovement(dto.getMonthsWithoutMovement());
        entity.setDetectedAt(dto.getDetectedAt());
        entity.setPlannedAction(dto.getPlannedAction());
        entity.setActionComment(dto.getActionComment());
    }

    private Material material(UUID id) {
        return id == null ? null : materialRepository.findByIdAndIsDeletedFalse(id).orElse(null);
    }

    private BigDecimal estimatedValue(NonMovingStock entity) {
        if (entity.getTenantId() == null
            || entity.getCompanyId() == null
            || entity.getMaterialId() == null
            || entity.getWarehouseId() == null) {
            return null;
        }
        var aggregate = stockBalanceRepository.aggregateMaterial360(
            entity.getTenantId(),
            entity.getCompanyId(),
            entity.getMaterialId(),
            entity.getWarehouseId()
        );
        return aggregate == null ? null : aggregate.getTotalValue();
    }
}
