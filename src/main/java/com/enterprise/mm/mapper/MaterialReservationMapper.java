package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.MaterialReservationResponse;
import com.enterprise.mm.payload.MaterialReservationUpdateRequest;
import com.enterprise.mm.payload.MaterialReservationCreateRequest;
import com.enterprise.mm.entity.MaterialReservation;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MaterialReservationMapper {
    private final Clock clock;
    public MaterialReservationResponse toResponse(MaterialReservation entity) {
        if (entity == null) {
            return null;
        }
        MaterialReservationResponse dto = new MaterialReservationResponse();
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
        dto.setUnitOfMeasure(entity.getMaterial() == null || entity.getMaterial().getUnitOfMeasure() == null
            ? null
            : firstNonBlank(
                entity.getMaterial().getUnitOfMeasure().getMeasurementNameShort(),
                entity.getMaterial().getUnitOfMeasure().getMeasurementNameShortRu(),
                entity.getMaterial().getUnitOfMeasure().getName()
            ));
        dto.setWarehouseId(entity.getWarehouseId());
        dto.setWarehouseCode(entity.getWarehouse() == null ? null : entity.getWarehouse().getCode());
        dto.setWarehouseName(entity.getWarehouse() == null ? null : entity.getWarehouse().getName());
        dto.setStorageLocationId(entity.getStorageLocationId());
        dto.setStorageLocationCode(entity.getStorageLocation() == null ? null : entity.getStorageLocation().getCode());
        dto.setStorageLocationName(entity.getStorageLocation() == null ? null : firstNonBlank(
            entity.getStorageLocation().getName(),
            entity.getStorageLocation().getBinCode(),
            entity.getStorageLocation().getCode()
        ));
        dto.setStorageBinId(entity.getStorageBinId());
        dto.setStorageBinCode(entity.getStorageBin() == null ? null : firstNonBlank(
            entity.getStorageBin().getBinCode(), entity.getStorageBin().getCode(), entity.getStorageBin().getName()));
        dto.setDispositionSnapshot(entity.getDispositionSnapshot());
        dto.setUomIdSnapshot(entity.getUomIdSnapshot());
        dto.setUomCodeSnapshot(entity.getUomCodeSnapshot());
        dto.setStockBatchId(entity.getStockBatchId());
        dto.setStockBatchCode(BaseMapper.stockBatchCode(entity.getStockBatch()));
        dto.setStockBatchNumber(entity.getStockBatch() == null ? null : entity.getStockBatch().getBatchNumber());
        dto.setStockBatchName(BaseMapper.stockBatchName(entity.getStockBatch()));
        dto.setDemandPlanId(entity.getDemandPlanId());
        dto.setDemandPlanCode(BaseMapper.code(entity.getDemandPlan()));
        dto.setDemandPlanName(BaseMapper.name(entity.getDemandPlan()));
        dto.setDemandPlanItemId(entity.getDemandPlanItemId());
        dto.setDemandPlanItemCode(BaseMapper.code(entity.getDemandPlanItem()));
        dto.setDemandPlanItemName(BaseMapper.name(entity.getDemandPlanItem()));
        dto.setProjectCode(entity.getProjectCode());
        dto.setRepairOrderCode(entity.getRepairOrderCode());
        dto.setReservationType(entity.getReservationType());
        dto.setPriority(entity.getPriority());
        dto.setQuantity(entity.getQuantity());
        dto.setReservedQuantity(entity.getReservedQuantity());
        dto.setReleasedQuantity(entity.getReleasedQuantity());
        dto.setConsumedQuantity(entity.getConsumedQuantity());
        dto.setCancelledQuantity(entity.getCancelledQuantity());
        dto.setExpiredQuantity(entity.getExpiredQuantity());
        dto.setRequiredDate(entity.getRequiredDate());
        dto.setExpiresAt(entity.getExpiresAt());
        dto.setReferenceType(entity.getReferenceType());
        dto.setReferenceId(entity.getReferenceId());
        dto.setReservedBy(entity.getReservedBy());
        dto.setReleasedAt(entity.getReleasedAt());
        dto.setReleasedBy(entity.getReleasedBy());
        dto.setConsumedAt(entity.getConsumedAt());
        dto.setConsumedBy(entity.getConsumedBy());
        dto.setCancelledAt(entity.getCancelledAt());
        dto.setCancelledBy(entity.getCancelledBy());
        dto.setCancellationReason(entity.getCancellationReason());
        dto.setExpiredAt(entity.getExpiredAt());
        dto.setExpiredBy(entity.getExpiredBy());
        dto.setExpirationReason(entity.getExpirationReason());
        dto.setTerminalAt(entity.getTerminalAt());
        dto.setTerminalBy(entity.getTerminalBy());
        dto.setTerminalReason(entity.getTerminalReason());
        dto.setTerminalEvidenceReference(entity.getTerminalEvidenceReference());
        if ("ACTIVE".equals(entity.getStatus()) || "PARTIALLY_RELEASED".equals(entity.getStatus())) {
            java.util.ArrayList<String> actions = new java.util.ArrayList<>(java.util.List.of("release", "consume", "cancel"));
            if (entity.getExpiresAt() != null && !entity.getExpiresAt().isAfter(LocalDateTime.now(clock))) {
                actions.add("expire");
            }
            dto.setAllowedActions(java.util.List.copyOf(actions));
        } else {
            dto.setAllowedActions(java.util.List.of());
        }
        return dto;
    }

    public MaterialReservation toEntity(MaterialReservationCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        MaterialReservation entity = new MaterialReservation();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(null);
        entity.setDescription(dto.getDescription());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setStorageLocationId(dto.getStorageLocationId());
        entity.setStorageBinId(dto.getStorageBinId());
        entity.setStockBatchId(dto.getStockBatchId());
        entity.setDemandPlanId(dto.getDemandPlanId());
        entity.setDemandPlanItemId(dto.getDemandPlanItemId());
        entity.setProjectCode(dto.getProjectCode());
        entity.setRepairOrderCode(dto.getRepairOrderCode());
        entity.setReservationType(dto.getReservationType());
        entity.setPriority(dto.getPriority());
        entity.setQuantity(dto.getQuantity());
        entity.setRequiredDate(dto.getRequiredDate());
        entity.setExpiresAt(dto.getExpiresAt());
        entity.setReferenceType(dto.getReferenceType());
        entity.setReferenceId(dto.getReferenceId());
        return entity;
    }

    public void updateEntity(MaterialReservation entity, MaterialReservationUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setPriority(dto.getPriority());
        entity.setRequiredDate(dto.getRequiredDate());
        entity.setExpiresAt(dto.getExpiresAt());
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
