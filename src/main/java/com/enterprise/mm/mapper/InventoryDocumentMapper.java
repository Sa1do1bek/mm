package com.enterprise.mm.mapper;

import com.enterprise.mm.enums.InventoryStatus;
import com.enterprise.mm.enums.InventoryType;
import com.enterprise.mm.payload.InventoryDocumentResponse;
import com.enterprise.mm.payload.InventoryDocumentUpdateRequest;
import com.enterprise.mm.payload.InventoryDocumentCreateRequest;
import com.enterprise.mm.entity.InventoryDocument;
import java.util.Locale;
import java.util.LinkedHashSet;
import org.springframework.stereotype.Component;

@Component
public class InventoryDocumentMapper {
    public InventoryDocumentResponse toResponse(InventoryDocument entity) {
        if (entity == null) {
            return null;
        }
        InventoryDocumentResponse dto = new InventoryDocumentResponse();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setCompanyId(entity.getCompanyId());
        dto.setCode(entity.getCode());
        dto.setInventoryNumber(entity.getInventoryNumber());
        dto.setName(entity.getName());
        dto.setStatus(entity.getStatus());
        dto.setInventoryStatus(parseEnum(InventoryStatus.class, entity.getStatus()));
        dto.setDescription(entity.getDescription());
        dto.setDeleted(entity.isDeleted());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setUpdatedBy(entity.getUpdatedBy());
        dto.setVersion(entity.getVersion());
        dto.setWarehouseId(entity.getWarehouseId());
        dto.setStorageLocationId(entity.getStorageLocationId());dto.setStorageBinId(entity.getStorageBinId());
        dto.setCountRevision(entity.getCountRevision());dto.setSubmittedCountRevision(entity.getSubmittedCountRevision());
        dto.setApprovedCountRevision(entity.getApprovedCountRevision());dto.setPostingDate(entity.getPostingDate());
        dto.setApprovalInstanceId(entity.getApprovalInstanceId()); dto.setCommissionApprovedRevision(entity.getCommissionApprovedRevision());
        dto.setCommissionApprovedBy(entity.getCommissionApprovedBy()); dto.setCommissionApprovedAt(entity.getCommissionApprovedAt());
        dto.setFinanceApprovedRevision(entity.getFinanceApprovedRevision()); dto.setFinanceApprovedBy(entity.getFinanceApprovedBy()); dto.setFinanceApprovedAt(entity.getFinanceApprovedAt());
        dto.setCorrectionReason(entity.getCorrectionReason()); dto.setUnidentifiedStock(entity.getUnidentifiedStock());
        dto.setWarehouseCode(BaseMapper.code(entity.getWarehouse()));
        dto.setWarehouseName(BaseMapper.name(entity.getWarehouse()));
        dto.setGlobalWarehouse(entity.isGlobalWarehouse());
        dto.setScopeMode(entity.getScopeMode());
        dto.setDepartmentIds(entity.getScopeMode() == null ? null : java.util.List.copyOf(entity.getDepartmentIds()));
        dto.setSelectedWarehouseIds(entity.getScopeMode() == null ? null : java.util.List.copyOf(entity.getSelectedWarehouseIds()));
        dto.setInventoryType(parseEnum(InventoryType.class, entity.getInventoryType()));
        dto.setPlannedStartDate(entity.getPlannedStartDate());
        dto.setPlannedEndDate(entity.getPlannedEndDate());
        dto.setStockAsOfDate(entity.getStockAsOfDate());
        dto.setActualStartDate(entity.getActualStartDate());
        dto.setActualEndDate(entity.getActualEndDate());
        dto.setResponsibleUserId(entity.getResponsibleUserId());
        dto.setCommissionId(entity.getCommissionId());
        dto.setCommissionCode(BaseMapper.code(entity.getCommission()));
        dto.setCommissionName(BaseMapper.name(entity.getCommission()));
        dto.setFreezeOperations(entity.isFreezeOperations());
        dto.setSnapshotAt(entity.getSnapshotAt());
        dto.setTotalItems(entity.getTotalItems());
        dto.setCountedItems(entity.getCountedItems());
        dto.setDiscrepancyItems(entity.getDiscrepancyItems());
        return dto;
    }

    public InventoryDocument toEntity(InventoryDocumentCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        InventoryDocument entity = new InventoryDocument();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(enumName(dto.getStatus()));
        entity.setDescription(dto.getDescription());
        entity.setCompanyId(dto.getCompanyId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setStorageLocationId(dto.getStorageLocationId());entity.setStorageBinId(dto.getStorageBinId());
        if (dto.getGlobalWarehouse() != null) {
            entity.setGlobalWarehouse(dto.getGlobalWarehouse());
        }
        entity.setScopeMode(dto.getScopeMode());
        if (dto.getDepartmentIds() != null) entity.setDepartmentIds(new LinkedHashSet<>(dto.getDepartmentIds()));
        if (dto.getWarehouseIds() != null) entity.setSelectedWarehouseIds(new LinkedHashSet<>(dto.getWarehouseIds()));
        entity.setInventoryType(enumName(dto.getInventoryType()));
        entity.setPlannedStartDate(dto.getPlannedStartDate());
        entity.setPlannedEndDate(dto.getPlannedEndDate());
        entity.setStockAsOfDate(dto.getStockAsOfDate());
        entity.setResponsibleUserId(dto.getResponsibleUserId());
        entity.setCommissionId(dto.getCommissionId());
        if (dto.getFreezeOperations() != null) {
            entity.setFreezeOperations(dto.getFreezeOperations());
        }
        return entity;
    }

    public void updateEntity(InventoryDocument entity, InventoryDocumentUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(enumName(dto.getStatus()));
        entity.setDescription(dto.getDescription());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setStorageLocationId(dto.getStorageLocationId());entity.setStorageBinId(dto.getStorageBinId());
        if (dto.getGlobalWarehouse() != null) {
            entity.setGlobalWarehouse(dto.getGlobalWarehouse());
        }
        if (dto.getScopeMode() != null) entity.setScopeMode(dto.getScopeMode());
        if (dto.getDepartmentIds() != null) entity.setDepartmentIds(new LinkedHashSet<>(dto.getDepartmentIds()));
        if (dto.getWarehouseIds() != null) entity.setSelectedWarehouseIds(new LinkedHashSet<>(dto.getWarehouseIds()));
        entity.setInventoryType(enumName(dto.getInventoryType()));
        entity.setPlannedStartDate(dto.getPlannedStartDate());
        entity.setPlannedEndDate(dto.getPlannedEndDate());
        entity.setStockAsOfDate(dto.getStockAsOfDate());
        entity.setResponsibleUserId(dto.getResponsibleUserId());
        entity.setCommissionId(dto.getCommissionId());
        if (dto.getFreezeOperations() != null) {
            entity.setFreezeOperations(dto.getFreezeOperations());
        }
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private <T extends Enum<T>> T parseEnum(Class<T> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
