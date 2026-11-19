package com.enterprise.mm.inventory.allocation;

import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.StockMovementAllocation;
import com.enterprise.mm.entity.StockMovementAllocationCostLayer;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockMovementAllocationWriter {
    private final EntityManager entityManager;
    private final StockMovementAllocationReferenceVerifier referenceVerifier;

    public StockMovementAllocationWriter(
        EntityManager entityManager,
        StockMovementAllocationReferenceVerifier referenceVerifier
    ) {
        this.entityManager = entityManager;
        this.referenceVerifier = referenceVerifier;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(StockMovement movement, StockMovementAllocationEvidence evidence) {
        validate(movement, evidence);
        referenceVerifier.verify(movement, evidence);

        LocalDateTime capturedAt = LocalDateTime.now();
        String capturedBy = currentActor();
        int allocationSequence = 1;
        for (StockMovementAllocationDraft draft : evidence.allocations()) {
            StockMovementAllocation allocation = allocation(
                movement,
                draft,
                allocationSequence++,
                capturedAt,
                capturedBy
            );
            entityManager.persist(allocation);
            if (allocation.getId() == null) {
                throw new IllegalStateException("Hibernate did not assign an allocation UUID during persist");
            }

            int lineageSequence = 1;
            for (StockMovementCostLineageDraft lineageDraft : draft.costLineages()) {
                entityManager.persist(lineage(
                    movement,
                    allocation.getId(),
                    lineageDraft,
                    lineageSequence++,
                    capturedAt,
                    capturedBy
                ));
            }
        }
        entityManager.flush();
    }

    private void validate(StockMovement movement, StockMovementAllocationEvidence evidence) {
        if (movement == null || movement.getId() == null || movement.getTenantId() == null
            || movement.getTenantId().isBlank() || movement.getCompanyId() == null
            || movement.getMaterialId() == null) {
            throw new IllegalArgumentException("Persisted movement ownership is required for allocation evidence");
        }
        if (evidence == null) {
            throw new IllegalArgumentException("Allocation evidence is required");
        }
        if (!Objects.equals(movement.getTenantId(), evidence.tenantId())
            || !Objects.equals(movement.getCompanyId(), evidence.companyId())
            || !Objects.equals(movement.getId(), evidence.movementId())
            || !Objects.equals(movement.getMaterialId(), evidence.materialId())
            || !Objects.equals(movement.getSourceInventoryItemId(), evidence.sourceInventoryItemId())) {
            throw new IllegalArgumentException("Allocation evidence scope does not match movement-owned scope");
        }
        if (evidence.allocations().isEmpty()) {
            throw new IllegalArgumentException("At least one allocation is required");
        }
        for (int index = 0; index < evidence.allocations().size(); index++) {
            validateAllocation(evidence.allocations().get(index), index + 1);
        }
    }

    private void validateAllocation(StockMovementAllocationDraft draft, int sequence) {
        if (draft == null) {
            throw new IllegalArgumentException("Allocation " + sequence + " is required");
        }
        allocationRole(draft.allocationRole());
        List<BigDecimal> deltas = List.of(
            requireDelta(draft.onHandDelta(), "on-hand", sequence),
            requireDelta(draft.availableDelta(), "available", sequence),
            requireDelta(draft.reservedDelta(), "reserved", sequence),
            requireDelta(draft.quarantineDelta(), "quarantine", sequence),
            requireDelta(draft.totalValueDelta(), "total-value", sequence),
            requireDelta(draft.batchQuantityDelta(), "batch-quantity", sequence)
        );
        if (deltas.stream().allMatch(delta -> delta.signum() == 0)) {
            throw new IllegalArgumentException("Allocation " + sequence + " cannot contain only zero deltas");
        }
        for (int index = 0; index < draft.costLineages().size(); index++) {
            validateLineage(draft.costLineages().get(index), sequence, index + 1);
        }
    }

    private void validateLineage(
        StockMovementCostLineageDraft lineage,
        int allocationSequence,
        int lineageSequence
    ) {
        if (lineage == null) {
            throw new IllegalArgumentException(
                "Cost lineage " + allocationSequence + "." + lineageSequence + " is required");
        }
        lineageRole(lineage.lineageRole());
        if (lineage.quantityDelta().signum() == 0 && lineage.valueDelta().signum() == 0) {
            throw new IllegalArgumentException(
                "Cost lineage " + allocationSequence + "." + lineageSequence + " cannot contain only zero deltas");
        }
    }

    private BigDecimal requireDelta(BigDecimal delta, String name, int sequence) {
        if (delta == null) {
            throw new IllegalArgumentException("Allocation " + sequence + " " + name + " delta is required");
        }
        return delta;
    }

    private StockMovementAllocation allocation(
        StockMovement movement,
        StockMovementAllocationDraft draft,
        int sequence,
        LocalDateTime capturedAt,
        String capturedBy
    ) {
        StockMovementAllocation allocation = new StockMovementAllocation();
        allocation.setTenantId(movement.getTenantId());
        allocation.setCompanyId(movement.getCompanyId());
        allocation.setCreatedAt(capturedAt);
        allocation.setCreatedBy(capturedBy);
        allocation.setMovementId(movement.getId());
        allocation.setAllocationSequence(sequence);
        allocation.setAllocationRole(allocationRole(draft.allocationRole()).name());
        allocation.setMaterialId(movement.getMaterialId());
        allocation.setBalanceId(draft.balanceId());
        mapSource(allocation, draft.sourceStockKey());
        mapTarget(allocation, draft.targetStockKey());
        allocation.setUomIdSnapshot(draft.uomIdSnapshot());
        allocation.setUomCodeSnapshot(draft.uomCodeSnapshot());
        allocation.setOnHandDelta(draft.onHandDelta());
        allocation.setAvailableDelta(draft.availableDelta());
        allocation.setReservedDelta(draft.reservedDelta());
        allocation.setQuarantineDelta(draft.quarantineDelta());
        allocation.setTotalValueDelta(draft.totalValueDelta());
        allocation.setBatchQuantityDelta(draft.batchQuantityDelta());
        allocation.setUnitCostSnapshot(draft.unitCostSnapshot());
        allocation.setCurrencyCodeSnapshot(draft.currencyCodeSnapshot());
        allocation.setExchangeRateSnapshot(draft.exchangeRateSnapshot());
        allocation.setSourceInventoryItemId(movement.getSourceInventoryItemId());
        return allocation;
    }

    private StockMovementAllocationCostLayer lineage(
        StockMovement movement,
        UUID allocationId,
        StockMovementCostLineageDraft draft,
        int sequence,
        LocalDateTime capturedAt,
        String capturedBy
    ) {
        StockMovementAllocationCostLayer lineage = new StockMovementAllocationCostLayer();
        lineage.setTenantId(movement.getTenantId());
        lineage.setCompanyId(movement.getCompanyId());
        lineage.setCreatedAt(capturedAt);
        lineage.setCreatedBy(capturedBy);
        lineage.setAllocationId(allocationId);
        lineage.setLineageSequence(sequence);
        lineage.setLineageRole(lineageRole(draft.lineageRole()).name());
        lineage.setCostLayerId(draft.costLayerId());
        lineage.setParentCostLayerIdSnapshot(draft.parentCostLayerIdSnapshot());
        lineage.setSourceCostMovementId(draft.sourceCostMovementId());
        lineage.setQuantityDelta(draft.quantityDelta());
        lineage.setValueDelta(draft.valueDelta());
        lineage.setUnitCostSnapshot(draft.unitCostSnapshot());
        lineage.setCurrencyCodeSnapshot(draft.currencyCodeSnapshot());
        lineage.setExchangeRateSnapshot(draft.exchangeRateSnapshot());
        return lineage;
    }

    private void mapSource(StockMovementAllocation allocation, StockMovementStockKeySnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        allocation.setSourceWarehouseIdSnapshot(snapshot.warehouseId());
        allocation.setSourceStorageLocationIdSnapshot(snapshot.storageLocationId());
        allocation.setSourceStorageBinIdSnapshot(snapshot.storageBinId());
        allocation.setSourceStockBatchIdSnapshot(snapshot.stockBatchId());
        allocation.setSourceBatchNumberSnapshot(snapshot.batchNumber());
        allocation.setSourceDispositionSnapshot(snapshot.disposition());
    }

    private void mapTarget(StockMovementAllocation allocation, StockMovementStockKeySnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        allocation.setTargetWarehouseIdSnapshot(snapshot.warehouseId());
        allocation.setTargetStorageLocationIdSnapshot(snapshot.storageLocationId());
        allocation.setTargetStorageBinIdSnapshot(snapshot.storageBinId());
        allocation.setTargetStockBatchIdSnapshot(snapshot.stockBatchId());
        allocation.setTargetBatchNumberSnapshot(snapshot.batchNumber());
        allocation.setTargetDispositionSnapshot(snapshot.disposition());
    }

    private StockMovementAllocationRole allocationRole(String role) {
        try {
            return StockMovementAllocationRole.valueOf(normalizeRole(role));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown allocation role: " + role, exception);
        }
    }

    private StockMovementCostLineageRole lineageRole(String role) {
        try {
            return StockMovementCostLineageRole.valueOf(normalizeRole(role));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown lineage role: " + role, exception);
        }
    }

    private String normalizeRole(String role) {
        return role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || !authentication.isAuthenticated() ? null : authentication.getName();
    }
}
