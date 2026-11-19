package com.enterprise.mm.service;

import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.*;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.inventory.*;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryProcessingService {
    private final InventoryDocumentRepository documents;
    private final InventoryItemRepository items;
    private final StockMovementService stock;
    private final StockMovementRepository movements;
    private final StockBatchRepository batches;
    private final AuditService audit;
    private final IdempotencyService idempotency;
    private final InventoryAdjustmentPermitService permit;
    private final InventoryRevisionService revisions;
    private final InventoryPostingReadinessService readiness;
    private final InventoryScopeLockService locks;
    private final InventoryScopeService scope;
    private final InventoryFoundStockService found;
    private final InventoryPeriodGuardService periods;
    private final MaterialRepository materialRepository;
    private final MaterialUnitEligibilityService materialUnitEligibilityService;
    private final StockValuationService stockValuationService;

    public InventoryProcessingService(
        InventoryDocumentRepository documents,
        InventoryItemRepository items,
        StockMovementService stock,
        StockMovementRepository movements,
        StockBatchRepository batches,
        AuditService audit,
        IdempotencyService idempotency,
        InventoryAdjustmentPermitService permit,
        InventoryRevisionService revisions,
        InventoryPostingReadinessService readiness,
        InventoryScopeLockService locks,
        InventoryScopeService scope,
        InventoryFoundStockService found,
        InventoryPeriodGuardService periods,
        MaterialRepository materialRepository,
        MaterialUnitEligibilityService materialUnitEligibilityService,
        StockValuationService stockValuationService
    ) {
        this.documents = documents;
        this.items = items;
        this.stock = stock;
        this.movements = movements;
        this.batches = batches;
        this.audit = audit;
        this.idempotency = idempotency;
        this.permit = permit;
        this.revisions = revisions;
        this.readiness = readiness;
        this.locks = locks;
        this.scope = scope;
        this.found = found;
        this.periods = periods;
        this.materialRepository = materialRepository;
        this.materialUnitEligibilityService = materialUnitEligibilityService;
        this.stockValuationService = stockValuationService;
    }

    @Transactional
    public InventoryAdjustmentPostingResponse postAdjustments(UUID id, InventoryAdjustmentPostRequest command, String key) {
        var d = revisions.lock(id); readiness.requirePostAuthority(d);
        if (command == null || command.expectedVersion() == null || command.approvedCountRevision() == null || command.postingDate() == null) throw new BadRequestException("Current document and approved count revisions and an explicit posting date are required");
        if (key == null || key.isBlank() || key.length() > 120) throw new BadRequestException("Idempotency-Key must contain 1 to 120 characters");
        return idempotency.execute("INVENTORY_ADJUSTMENT_POST", id, key, command, InventoryAdjustmentPostingResponse.class, () -> {
            if (Set.of("ADJUSTMENT_POSTED", "CLOSED").contains(d.getStatus())) return response(d, true);
            if (!Objects.equals(d.getVersion(), command.expectedVersion())) throw new ConflictException("The count changed. Refresh its approved version before posting.", "DOCUMENT_VERSION_CONFLICT");
            if (!Objects.equals(d.getApprovedCountRevision(), command.approvedCountRevision())) throw new ConflictException("This approved count revision is stale.", "APPROVAL_REVISION_STALE");
            var rows = items.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalse(d.getTenantId(), id);
            if (rows.stream().anyMatch(l -> l.isFoundStock() && l.getObservedSerialNumber() != null)) locks.lock(d.getCompanyId(), List.of());
            else scope.lock(d);
            readiness.requireReady(d, command.postingDate());
            if (!movements.findByTenantIdAndReferenceTypeAndReferenceIdAndIsDeletedFalse(d.getTenantId(), "InventoryDocument", id).isEmpty()) throw new BusinessException("Existing inventory movements require reconciliation before retrying posting");
            for (var line : rows) periods.ensureOpen(line.getWarehouseId(), command.postingDate(), "inventory adjustment");
            d.setPostingDate(command.postingDate());
            return permit.withApprovedCount(id, d.getCountRevision(), () -> post(d, rows, command));
        });
    }
    private InventoryAdjustmentPostingResponse post(InventoryDocument d, List<InventoryItem> rows, InventoryAdjustmentPostRequest command) {
        Map<UUID, List<UUID>> selectedUnitIds = new HashMap<>();
        if (command.lines() != null) {
            for (var line : command.lines()) {
                if (line != null && line.inventoryItemId() != null) {
                    selectedUnitIds.put(line.inventoryItemId(), line.materialUnitIds() == null ? List.of() : line.materialUnitIds());
                }
            }
        }
        // Stable ordering makes rollback/error recovery and ledger inspection reproducible.
        for (var line : rows.stream().sorted(Comparator.comparing(InventoryItem::getId)).toList()) {
            if (line.getCountedQuantity() == null) continue;
            BigDecimal systemQty = line.getSystemQuantity() == null ? BigDecimal.ZERO : line.getSystemQuantity();
            BigDecimal difference = line.getCountedQuantity().subtract(systemQty);
            if (difference.signum() == 0) continue;
            Material material = materialRepository.findByIdAndIsDeletedFalse(line.getMaterialId())
                .orElseThrow(() -> new BusinessException("Material not found: " + line.getMaterialId()));
            boolean serialized = material.getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED;
            if (line.isFoundStock()) {
                if (line.getObservedSerialNumber() != null) found.requireSerialAvailable(d, line.getObservedSerialNumber(), line.getId());
                if (serialized && difference.signum() > 0)
                    throw new BusinessException("SERIALIZED_POSITIVE_ADJUSTMENT_REQUIRES_RECEIPT_LINEAGE");
                if (line.getStockBatchId() == null && (line.getObservedBatchNumber() != null || line.getObservedSerialNumber() != null || line.getObservedExpiryDate() != null)) {
                    var batch = new StockBatch(); batch.setTenantId(d.getTenantId()); batch.setCompanyId(d.getCompanyId()); batch.setMaterialId(line.getMaterialId()); batch.setWarehouseId(line.getWarehouseId());
                    batch.setBatchNumber(line.getObservedBatchNumber() == null ? "COUNT-" + line.getId().toString().substring(0, 8) : line.getObservedBatchNumber());
                    batch.setName(batch.getBatchNumber()); batch.setSerialNumber(line.getObservedSerialNumber()); batch.setExpirationDate(line.getObservedExpiryDate()); batch.setReceivedDate(d.getPostingDate());
                    batch.setQuantity(BigDecimal.ZERO); batch.setUnitCost(line.getUnitCost()); batch.setPurchaseUnitCost(line.getUnitCost()); batch.setFinalUnitCost(line.getUnitCost());
                    batch = batches.saveAndFlush(batch); line.setStockBatchId(batch.getId()); line.setStockBatch(batch); items.saveAndFlush(line);
                }
            }
            List<MaterialUnit> adjustedUnits = List.of();
            if (serialized) {
                if (difference.signum() > 0) {
                    throw new BusinessException("SERIALIZED_POSITIVE_ADJUSTMENT_REQUIRES_RECEIPT_LINEAGE");
                }
                adjustedUnits = materialUnitEligibilityService.lockAndValidate(
                    line.getMaterialId(),
                    line.getWarehouseId(),
                    line.getStorageLocationId(),
                    line.getStorageBinId(),
                    difference.abs(),
                    selectedUnitIds.get(line.getId()),
                    MaterialUnitStatus.IN_STOCK
                );
                if (adjustedUnits.stream().anyMatch(unit -> !Objects.equals(unit.getStockBatchId(), line.getStockBatchId()))) {
                    throw new BadRequestException("MATERIAL_UNIT_WRONG_BATCH");
                }
            }
            var movement = new StockMovement(); movement.setName("Physical inventory adjustment"); movement.setCode("INV-" + line.getId());
            movement.setTenantId(d.getTenantId()); movement.setCompanyId(d.getCompanyId()); movement.setMaterialId(line.getMaterialId()); movement.setWarehouseId(line.getWarehouseId());
            movement.setStorageLocationId(line.getStorageLocationId()); movement.setStorageBinId(line.getStorageBinId()); movement.setStockBatchId(line.getStockBatchId());
            movement.setMovementType(difference.signum() > 0 ? MovementType.ADJUSTMENT : MovementType.WRITE_OFF); movement.setQuantity(difference.abs());
            movement.setUnitCost(difference.signum() > 0 ? positiveAdjustmentUnitCost(line) : BigDecimal.ZERO); movement.setMovementDate(d.getPostingDate());
            movement.setReferenceType("InventoryDocument"); movement.setReferenceId(d.getId()); movement.setSourceInventoryItemId(line.getId());
            var posted = stock.createAndPostManagedInventoryAdjustment(movement);
            if (posted == null || posted.getId() == null) throw new BusinessException("Inventory adjustment did not return a posted movement");
            if (serialized) {
                materialUnitEligibilityService.markAdjustedOut(
                    adjustedUnits,
                    "InventoryDocument",
                    d.getId(),
                    line.getId(),
                    line.getWarehouseId(),
                    line.getStorageLocationId(),
                    line.getStorageBinId()
                );
            }
            line.setLinkedStockMovementId(posted.getId()); items.save(line);
        }
        d.setStatus("ADJUSTMENT_POSTED"); documents.saveAndFlush(d);
        audit.log("InventoryDocument", d.getId(), "POST_ADJUSTMENTS", "Count revision " + d.getCountRevision() + ", posting date " + d.getPostingDate(), null, null);
        return response(d, false);
    }
    private InventoryAdjustmentPostingResponse response(InventoryDocument d, boolean replay) {
        return new InventoryAdjustmentPostingResponse(d.getId(), d.getStatus(), items.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalse(d.getTenantId(), d.getId()).stream().map(InventoryItem::getLinkedStockMovementId).filter(Objects::nonNull).sorted().toList(), d.getVersion(), d.getApprovedCountRevision(), d.getPostingDate(), replay);
    }

    private BigDecimal positiveAdjustmentUnitCost(InventoryItem line) {
        BigDecimal lineUnitCost = line.getUnitCost() == null ? BigDecimal.ZERO : line.getUnitCost();
        return lineUnitCost.signum() > 0
            ? lineUnitCost
            : stockValuationService.calculateActualCost(line.getMaterialId(), List.of(line.getWarehouseId()), line.getStockBatchId(), BigDecimal.ONE);
    }
}
