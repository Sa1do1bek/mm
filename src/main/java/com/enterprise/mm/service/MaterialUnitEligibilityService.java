package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.MaterialUnit;
import com.enterprise.mm.entity.MaterialUnitEvent;
import com.enterprise.mm.entity.MaterialUnitOperation;
import com.enterprise.mm.enums.MaterialUnitEventType;
import com.enterprise.mm.enums.MaterialUnitOperationType;
import com.enterprise.mm.enums.MaterialUnitStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.MaterialUnitEventRepository;
import com.enterprise.mm.repository.MaterialUnitOperationRepository;
import com.enterprise.mm.repository.MaterialUnitRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@com.enterprise.mm.security.pilot.PilotCapabilityBoundary(value = com.enterprise.mm.security.pilot.PilotCapability.SERIALIZED_EXECUTION, reads = {"operationUnitIds", "lockAndValidate"})
public class MaterialUnitEligibilityService {
    private final MaterialUnitRepository materialUnitRepository;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;
    private final MaterialUnitEventRepository eventRepository;
    private final MaterialUnitOperationRepository operationRepository;

    public List<MaterialUnit> reserve(
        UUID materialId,
        UUID warehouseId,
        UUID storageLocationId,
        UUID storageBinId,
        BigDecimal quantity,
        List<UUID> materialUnitIds,
        String referenceType,
        UUID referenceId,
        UUID referenceLineId
    ) {
        List<MaterialUnit> units = lockAndValidate(
            materialId,
            warehouseId,
            storageLocationId,
            storageBinId,
            quantity,
            materialUnitIds,
            MaterialUnitStatus.IN_STOCK
        );
        transition(
            units,
            MaterialUnitStatus.RESERVED,
            MaterialUnitEventType.RESERVED,
            MaterialUnitOperationType.RESERVATION,
            referenceType,
            referenceId,
            referenceLineId,
            warehouseId,
            storageLocationId,
            storageBinId,
            null
        );
        return units;
    }

    public List<MaterialUnit> releaseReservation(
        UUID materialId,
        UUID warehouseId,
        UUID storageLocationId,
        UUID storageBinId,
        BigDecimal quantity,
        List<UUID> materialUnitIds,
        String referenceType,
        UUID referenceId,
        UUID referenceLineId
    ) {
        List<MaterialUnit> units = lockAndValidate(
            materialId,
            warehouseId,
            storageLocationId,
            storageBinId,
            quantity,
            materialUnitIds,
            MaterialUnitStatus.RESERVED
        );
        transition(
            units,
            MaterialUnitStatus.IN_STOCK,
            MaterialUnitEventType.RESERVATION_RELEASED,
            MaterialUnitOperationType.RESERVATION,
            referenceType,
            referenceId,
            referenceLineId,
            warehouseId,
            storageLocationId,
            storageBinId,
            null
        );
        return units;
    }

    public void markIssued(
        Collection<MaterialUnit> units,
        String referenceType,
        UUID referenceId,
        UUID referenceLineId,
        UUID warehouseId,
        UUID storageLocationId,
        UUID storageBinId
    ) {
        transition(
            units,
            MaterialUnitStatus.ISSUED,
            MaterialUnitEventType.ISSUED,
            MaterialUnitOperationType.ISSUE,
            referenceType,
            referenceId,
            referenceLineId,
            warehouseId,
            storageLocationId,
            storageBinId,
            null
        );
    }

    public List<MaterialUnit> dispatchTransfer(
        UUID materialId,
        UUID warehouseId,
        UUID storageLocationId,
        UUID storageBinId,
        BigDecimal quantity,
        List<UUID> materialUnitIds,
        String referenceType,
        UUID referenceId,
        UUID referenceLineId
    ) {
        List<MaterialUnit> units = lockAndValidate(
            materialId,
            warehouseId,
            storageLocationId,
            storageBinId,
            quantity,
            materialUnitIds,
            MaterialUnitStatus.IN_STOCK
        );
        transition(
            units,
            MaterialUnitStatus.IN_TRANSIT,
            MaterialUnitEventType.TRANSFER_DISPATCHED,
            MaterialUnitOperationType.TRANSFER,
            referenceType,
            referenceId,
            referenceLineId,
            warehouseId,
            storageLocationId,
            storageBinId,
            null
        );
        return units;
    }

    /** Exact stock position; do not wait on units while holding cost and balance locks. */
    public List<MaterialUnit> dispatchTransferExact(UUID materialId, UUID warehouseId, UUID storageLocationId,
        UUID storageBinId, BigDecimal quantity, List<UUID> materialUnitIds,
        String referenceType, UUID referenceId, UUID referenceLineId) {
        var selected = lockAndValidate(materialId, warehouseId, storageLocationId, storageBinId,
            quantity, materialUnitIds, MaterialUnitStatus.IN_STOCK, true);
        if (selected.stream().anyMatch(u -> !Objects.equals(storageLocationId, u.getStorageLocationId())
            || !Objects.equals(storageBinId, u.getStorageBinId())))
            throw new BusinessException("MATERIAL_UNIT_WRONG_LOCATION");
        transition(selected, MaterialUnitStatus.IN_TRANSIT, MaterialUnitEventType.TRANSFER_DISPATCHED,
            MaterialUnitOperationType.TRANSFER, referenceType, referenceId, referenceLineId,
            warehouseId, storageLocationId, storageBinId, null);
        return selected;
    }

    public List<MaterialUnit> receiveTransfer(
        UUID materialId,
        UUID sourceWarehouseId,
        UUID destinationWarehouseId,
        UUID destinationStorageLocationId,
        UUID destinationStorageBinId,
        BigDecimal quantity,
        List<UUID> materialUnitIds,
        String referenceType,
        UUID referenceId,
        UUID referenceLineId
    ) {
        List<MaterialUnit> units = lockAndValidate(
            materialId,
            sourceWarehouseId,
            null,
            null,
            quantity,
            materialUnitIds,
            MaterialUnitStatus.IN_TRANSIT
        );
        transition(
            units,
            MaterialUnitStatus.IN_STOCK,
            MaterialUnitEventType.TRANSFER_RECEIVED,
            MaterialUnitOperationType.TRANSFER,
            referenceType,
            referenceId,
            referenceLineId,
            destinationWarehouseId,
            destinationStorageLocationId,
            destinationStorageBinId,
            null
        );
        return units;
    }

    public List<MaterialUnit> receiveTransferWithDisposition(UUID materialId, UUID sourceWarehouseId, UUID sourceBatchId,
        String serialNumber, UUID destinationWarehouseId, UUID locationId, UUID binId, UUID destinationBatchId,
        BigDecimal quantity, List<UUID> unitIds, boolean quarantine, String referenceType, UUID referenceId, UUID lineId) {
        if (referenceType == null || referenceType.isBlank() || referenceId == null || lineId == null)
            throw new BusinessException("SERIAL_UNIT_REFERENCE_REQUIRED");
        List<MaterialUnit> units = lockAndValidate(materialId, sourceWarehouseId, null, null, quantity, unitIds, MaterialUnitStatus.IN_TRANSIT, false, true);
        if (destinationBatchId == null) throw new BusinessException("Transfer receipt destination batch is required");
        for (var unit : units) {
            if (!Objects.equals(sourceBatchId, unit.getStockBatchId())) throw new BusinessException("MATERIAL_UNIT_WRONG_BATCH");
            if (serialNumber != null && !serialNumber.isBlank() && !Objects.equals(serialNumber, unit.getManufacturerSerialNumber()))
                throw new BusinessException("MATERIAL_UNIT_WRONG_SERIAL");
        }
        var dispatched = new LinkedHashSet<>(operationRepository.findTransferDispatchUnitIds(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), referenceType, referenceId, lineId));
        if (!dispatched.containsAll(unitIds))
            throw new BusinessException("Select physical units dispatched for this transfer position", "transfers.receipt.serialWrongLine",
                Map.of("transferLineId", lineId, "field", quarantine ? "damagedMaterialUnitIds" : "materialUnitIds"));
        units.forEach(unit -> unit.setStockBatchId(destinationBatchId));
        transition(units, quarantine ? MaterialUnitStatus.QUARANTINE : MaterialUnitStatus.IN_STOCK,
            MaterialUnitEventType.TRANSFER_RECEIVED, MaterialUnitOperationType.TRANSFER, referenceType, referenceId, lineId,
            destinationWarehouseId, locationId, binId, null);
        return units;
    }

    public void markReturned(
        Collection<MaterialUnit> units,
        String referenceType,
        UUID referenceId,
        UUID referenceLineId,
        UUID warehouseId,
        UUID storageLocationId,
        UUID storageBinId
    ) {
        transition(
            units,
            MaterialUnitStatus.IN_STOCK,
            MaterialUnitEventType.RETURNED,
            MaterialUnitOperationType.RETURN,
            referenceType,
            referenceId,
            referenceLineId,
            warehouseId,
            storageLocationId,
            storageBinId,
            null
        );
    }

    public void markAdjustedOut(
        Collection<MaterialUnit> units,
        String referenceType,
        UUID referenceId,
        UUID referenceLineId,
        UUID warehouseId,
        UUID storageLocationId,
        UUID storageBinId
    ) {
        transition(
            units,
            MaterialUnitStatus.DISPOSED,
            MaterialUnitEventType.ADJUSTED_OUT,
            MaterialUnitOperationType.ADJUSTMENT,
            referenceType,
            referenceId,
            referenceLineId,
            warehouseId,
            storageLocationId,
            storageBinId,
            null
        );
    }

    public List<UUID> operationUnitIds(
        String referenceType,
        UUID referenceId,
        MaterialUnitOperationType operationType
    ) {
        if (referenceType == null || referenceType.isBlank() || referenceId == null || operationType == null) {
            return List.of();
        }
        return operationRepository.findUnitIds(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            referenceType,
            referenceId,
            operationType
        );
    }

    public List<MaterialUnit> lockAndValidate(
        UUID materialId,
        UUID warehouseId,
        UUID storageLocationId,
        UUID storageBinId,
        BigDecimal quantity,
        List<UUID> materialUnitIds,
        MaterialUnitStatus expectedStatus
    ) {
        return lockAndValidate(materialId, warehouseId, storageLocationId, storageBinId, quantity,
            materialUnitIds, expectedStatus, false);
    }

    private List<MaterialUnit> lockAndValidate(UUID materialId, UUID warehouseId, UUID storageLocationId,
        UUID storageBinId, BigDecimal quantity, List<UUID> materialUnitIds, MaterialUnitStatus expectedStatus,
        boolean nonblocking) {
        return lockAndValidate(materialId, warehouseId, storageLocationId, storageBinId, quantity, materialUnitIds, expectedStatus, nonblocking, nonblocking);
    }

    private List<MaterialUnit> lockAndValidate(UUID materialId, UUID warehouseId, UUID storageLocationId,
        UUID storageBinId, BigDecimal quantity, List<UUID> materialUnitIds, MaterialUnitStatus expectedStatus,
        boolean nonblocking, boolean refreshLockedState) {
        int expectedCount = exactUnitCount(quantity);
        if (materialUnitIds == null || materialUnitIds.isEmpty()) {
            throw new BusinessException("SERIAL_UNIT_SELECTION_REQUIRED");
        }
        if (materialUnitIds.size() != expectedCount) {
            throw new BusinessException("SERIAL_UNIT_COUNT_MISMATCH");
        }
        if (new LinkedHashSet<>(materialUnitIds).size() != materialUnitIds.size()) {
            throw new BusinessException("DUPLICATE_MATERIAL_UNIT_SELECTION");
        }
        List<MaterialUnit> locked = nonblocking
            ? materialUnitRepository.findForTransferUpdate(TenantContext.currentTenant(), CompanyContext.currentCompany(), materialUnitIds)
            : materialUnitRepository.findAllForUpdate(TenantContext.currentTenant(), CompanyContext.currentCompany(), materialUnitIds);
        // Picking or receipt context may already have read these entities. A locking query
        // does not refresh JPA's first-level cache, so validate the locked DB state.
        if (refreshLockedState && entityManager != null) locked.forEach(entityManager::refresh);
        if (locked.size() != materialUnitIds.size()) {
            throw new BusinessException("MATERIAL_UNIT_NOT_AVAILABLE");
        }
        Map<UUID, MaterialUnit> byId = new LinkedHashMap<>();
        for (MaterialUnit unit : locked) {
            byId.put(unit.getId(), unit);
        }
        List<MaterialUnit> ordered = new ArrayList<>();
        for (UUID unitId : materialUnitIds) {
            MaterialUnit unit = byId.get(unitId);
            if (unit == null) {
                throw new BusinessException("MATERIAL_UNIT_NOT_AVAILABLE");
            }
            validateScope(unit, materialId, warehouseId, storageLocationId, storageBinId, expectedStatus);
            ordered.add(unit);
        }
        return ordered;
    }

    public void transition(
        Collection<MaterialUnit> units,
        MaterialUnitStatus toStatus,
        MaterialUnitEventType eventType,
        MaterialUnitOperationType operationType,
        String referenceType,
        UUID referenceId,
        UUID referenceLineId,
        UUID toWarehouseId,
        UUID toStorageLocationId,
        UUID toStorageBinId,
        String operationKey
    ) {
        if (units == null || units.isEmpty()) {
            return;
        }
        if (referenceType == null || referenceType.isBlank() || referenceId == null) {
            throw new BusinessException("SERIAL_UNIT_REFERENCE_REQUIRED");
        }
        LocalDateTime occurredAt = LocalDateTime.now();
        for (MaterialUnit unit : units) {
            MaterialUnitStatus fromStatus = unit.getUnitStatus();
            UUID fromWarehouseId = unit.getWarehouseId();
            UUID fromLocationId = unit.getStorageLocationId();
            UUID fromBinId = unit.getStorageBinId();
            unit.setUnitStatus(toStatus);
            unit.setStatus(toStatus.name());
            if (toWarehouseId != null) {
                unit.setWarehouseId(toWarehouseId);
            }
            unit.setStorageLocationId(toStorageLocationId);
            unit.setStorageBinId(toStorageBinId);
            materialUnitRepository.save(unit);
            operationRepository.save(operation(
                unit,
                operationType,
                referenceType,
                referenceId,
                referenceLineId,
                fromStatus,
                toStatus,
                fromWarehouseId,
                fromLocationId,
                fromBinId,
                unit.getWarehouseId(),
                unit.getStorageLocationId(),
                unit.getStorageBinId(),
                operationKey,
                occurredAt
            ));
            eventRepository.save(event(unit, eventType, referenceType, referenceId, occurredAt));
        }
    }

    private void validateScope(
        MaterialUnit unit,
        UUID materialId,
        UUID warehouseId,
        UUID storageLocationId,
        UUID storageBinId,
        MaterialUnitStatus expectedStatus
    ) {
        if (!Objects.equals(materialId, unit.getMaterialId())) {
            throw new BusinessException("MATERIAL_UNIT_WRONG_MATERIAL");
        }
        if (!Objects.equals(warehouseId, unit.getWarehouseId())) {
            throw new BusinessException("MATERIAL_UNIT_WRONG_WAREHOUSE");
        }
        if (storageLocationId != null && !Objects.equals(storageLocationId, unit.getStorageLocationId())) {
            throw new BusinessException("MATERIAL_UNIT_WRONG_LOCATION");
        }
        if (storageBinId != null && !Objects.equals(storageBinId, unit.getStorageBinId())) {
            throw new BusinessException("MATERIAL_UNIT_WRONG_LOCATION");
        }
        if (expectedStatus != null && unit.getUnitStatus() != expectedStatus) {
            throw switch (unit.getUnitStatus()) {
                case RESERVED -> new BusinessException("MATERIAL_UNIT_ALREADY_RESERVED");
                case ISSUED -> new BusinessException("MATERIAL_UNIT_ALREADY_ISSUED");
                case IN_TRANSIT -> new BusinessException("MATERIAL_UNIT_IN_TRANSIT");
                default -> new BusinessException("MATERIAL_UNIT_NOT_AVAILABLE");
            };
        }
    }

    private int exactUnitCount(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new BusinessException("SERIAL_UNIT_COUNT_MISMATCH");
        }
        try {
            return quantity.stripTrailingZeros().toBigIntegerExact().intValueExact();
        } catch (ArithmeticException ex) {
            throw new BusinessException("SERIAL_UNIT_COUNT_MISMATCH");
        }
    }

    private MaterialUnitOperation operation(
        MaterialUnit unit,
        MaterialUnitOperationType operationType,
        String referenceType,
        UUID referenceId,
        UUID referenceLineId,
        MaterialUnitStatus fromStatus,
        MaterialUnitStatus toStatus,
        UUID fromWarehouseId,
        UUID fromLocationId,
        UUID fromBinId,
        UUID toWarehouseId,
        UUID toLocationId,
        UUID toBinId,
        String operationKey,
        LocalDateTime occurredAt
    ) {
        MaterialUnitOperation operation = new MaterialUnitOperation();
        operation.setTenantId(TenantContext.currentTenant());
        operation.setCompanyId(CompanyContext.currentCompany());
        operation.setName("Material unit " + operationType.name().toLowerCase(java.util.Locale.ROOT));
        operation.setMaterialUnitId(unit.getId());
        operation.setOperationType(operationType);
        operation.setReferenceType(referenceType);
        operation.setReferenceId(referenceId);
        operation.setReferenceLineId(referenceLineId);
        operation.setFromStatus(fromStatus == null ? null : fromStatus.name());
        operation.setToStatus(toStatus.name());
        operation.setFromWarehouseId(fromWarehouseId);
        operation.setToWarehouseId(toWarehouseId);
        operation.setFromStorageLocationId(fromLocationId);
        operation.setToStorageLocationId(toLocationId);
        operation.setFromStorageBinId(fromBinId);
        operation.setToStorageBinId(toBinId);
        operation.setStockBatchId(unit.getStockBatchId());
        operation.setOperationKey(operationKey);
        operation.setOccurredAt(occurredAt);
        return operation;
    }

    private MaterialUnitEvent event(
        MaterialUnit unit,
        MaterialUnitEventType eventType,
        String referenceType,
        UUID referenceId,
        LocalDateTime occurredAt
    ) {
        MaterialUnitEvent event = new MaterialUnitEvent();
        event.setTenantId(TenantContext.currentTenant());
        event.setCompanyId(CompanyContext.currentCompany());
        event.setName("Material unit " + eventType.name().toLowerCase(java.util.Locale.ROOT));
        event.setMaterialUnitId(unit.getId());
        event.setEventType(eventType);
        event.setSourceType(referenceType);
        event.setSourceId(referenceId);
        event.setWarehouseId(unit.getWarehouseId());
        event.setStorageLocationId(unit.getStorageLocationId());
        event.setStorageBinId(unit.getStorageBinId());
        event.setStockBatchId(unit.getStockBatchId());
        event.setPerformedBy(username());
        event.setOccurredAt(occurredAt);
        return event;
    }

    private String username() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null ? "system" : authentication.getName();
    }
}
