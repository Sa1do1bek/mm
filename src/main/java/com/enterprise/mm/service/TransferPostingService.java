package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.StockCostMovementType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.transfer.TransferTransitLedgerService;
import com.enterprise.mm.service.transfer.TransferValueSplit;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomic internal posting; HTTP commands authorize the business action before entering here. */
@Service @RequiredArgsConstructor
public class TransferPostingService {
    private final TransferDocumentRepository transfers;
    private final TransferDocumentLineRepository lines;
    private final MaterialRepository materials;
    private final TransferShipmentRepository shipments;
    private final TransferShipmentAllocationRepository allocations;
    private final TransferAllocationClearanceRepository clearances;
    private final StockMovementAllocationRepository stockAllocations;
    private final StockMovementAllocationCostLayerRepository costLineages;
    private final StockBatchRepository batches;
    private final StorageLocationRepository locations;
    private final StorageBinRepository bins;
    private final StockMovementRepository movements;
    private final StockMovementService stockMovementService;
    private final TransferTransitLedgerService transit;
    private final StockCostLedgerService costLedger;
    private final InventoryPeriodGuardService periodGuard;
    private final RoleDataScopeService dataScope;
    private final FinancialAuthorizationService financialAuthorization;
    private final IntegrationEventService events;
    private final com.enterprise.mm.service.transfer.TransferExceptionStateService exceptionState;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.enterprise.mm.erp.warehouse.outbound.WarehouseOutboundService warehouseOutbound;

    public record PostedTransferStock(UUID movementId, List<UUID> stockAllocationIds, BigDecimal quantity, BigDecimal bookValue, UUID destinationStockBatchId) {}

    @Transactional
    public PostedTransferStock postWithEvidence(TransferPostingContext context, StockMovement movement, List<UUID> attachmentIds) {
        var evidence = exceptionState.validateEvidence(context.transferId(), attachmentIds);
        var result = post(context, movement);
        if (context.kind() == TransferPostingContext.Kind.QUARANTINE_RECEIPT)
            exceptionState.attachReceiptEvidence(context.allocationId(), result.movementId(), evidence);
        return result;
    }

    @Transactional
    public PostedTransferStock post(TransferPostingContext context, StockMovement movement) {
        var transfer = transfers.findScopedForUpdate(context.transferId(), TenantContext.currentTenant(), CompanyContext.currentCompany())
            .orElseThrow(() -> new BusinessException("Transfer is outside the current company"));
        if (!Set.of("APPROVED", "DISPATCHED", "IN_TRANSIT", "RECEIVED", "DISCREPANCY_REVIEW").contains(transfer.getStatus()))
            throw new BusinessException("Transfer is not approved for stock operations");
        var shipment = shipments.findById(context.shipmentId()).orElseThrow(() -> new BusinessException("Shipment not found"));
        requireScope(shipment);
        if (!transfer.getId().equals(shipment.getTransferDocumentId())) throw new BusinessException("Shipment belongs to another transfer");
        validateQuantity(movement.getQuantity(), StockUomPolicy.MAX_QUANTITY_SCALE);
        if (movement.getMovementDate() == null) throw new BusinessException("Actual posting date is required");
        if (movement.getId() != null) throw new BusinessException("A transfer operation must create a new movement");
        boolean dispatch = context.kind() == TransferPostingContext.Kind.DISPATCH || context.kind() == TransferPostingContext.Kind.QUARANTINE_RETURN_DISPATCH;
        boolean returned = context.kind() == TransferPostingContext.Kind.TRANSIT_RETURN;
        boolean loss = context.kind() == TransferPostingContext.Kind.TRANSIT_LOSS;
        UUID warehouseId = dispatch || returned || loss ? transfer.getSourceWarehouseId() : transfer.getDestinationWarehouseId();
        if (!Objects.equals(warehouseId, movement.getWarehouseId()) || !dataScope.canAccessWarehouse(warehouseId))
            throw new BusinessException("Operation requires authority at the posting warehouse");
        validateLocation(movement, warehouseId);
        periodGuard.ensureOpen(warehouseId, movement.getMovementDate(), "transfer " + context.kind());
        movement.setTenantId(transfer.getTenantId()); movement.setCompanyId(transfer.getCompanyId());
        movement.setReferenceType("TRANSFER_" + context.kind()); movement.setReferenceId(transfer.getId());
        movement.setMovementType(dispatch ? MovementType.TRANSFER : loss ? MovementType.WRITE_OFF : MovementType.RECEIPT);
        if (dispatch) return dispatch(context, transfer, movement);

        var origin = allocations.findScopedForUpdate(context.allocationId(), transfer.getTenantId(), transfer.getCompanyId())
            .orElseThrow(() -> new BusinessException("Shipment allocation not found"));
        if (!origin.getShipmentId().equals(shipment.getId()) || !origin.getTransferDocumentId().equals(transfer.getId())
            || !origin.getMaterialId().equals(movement.getMaterialId())) throw new BusinessException("Receipt allocation does not match the transfer and material");
        validateQuantity(movement.getQuantity(), origin.getQuantityScale());
        if (context.kind() == TransferPostingContext.Kind.QUARANTINE_RELEASE || context.kind() == TransferPostingContext.Kind.QUARANTINE_WRITE_OFF) {
            if (context.kind() == TransferPostingContext.Kind.QUARANTINE_WRITE_OFF) financialAuthorization.requireFinance("write off quarantined transfer stock");
            else if (!financialAuthorization.hasAnyRole("ADMIN", "MM_MANAGER"))
                throw new BusinessException("Quality disposition authority is required to release quarantine");
            UUID dispositionBatchId = movement.getStockBatchId();
            boolean matchedReceipt = clearances.findByTenantIdAndCompanyIdAndAllocationIdOrderByCreatedAtAscIdAsc(transfer.getTenantId(), transfer.getCompanyId(), origin.getId())
                .stream().anyMatch(c -> c.getKind() == TransferAllocationClearance.Kind.QUARANTINE_RECEIPT
                    && Objects.equals(c.getDestinationStockBatchId(), dispositionBatchId));
            if (!matchedReceipt) throw new BusinessException("Selected batch has no quarantined receipt for this allocation");
            movement.setMovementType(context.kind() == TransferPostingContext.Kind.QUARANTINE_WRITE_OFF ? MovementType.WRITE_OFF : MovementType.TRANSFER);
            movement = stockMovementService.postManagedTransfer(context, movement, origin, null);
            emit(context, origin, movement, movement.getQuantity(), movement.getTotalCost());
            return result(movement);
        }
        var remaining = transit.balance(origin.getId());
        if (movement.getQuantity().compareTo(remaining.quantity()) > 0) throw new BusinessException("Receipt exceeds unresolved shipment quantity");
        if (origin.getSerialNumber() != null && !origin.getSerialNumber().isBlank() && movement.getQuantity().stripTrailingZeros().scale() > 0)
            throw new BusinessException("Serialized stock requires whole quantities");
        BigDecimal value = TransferValueSplit.take(remaining.quantity(), remaining.bookValue(), movement.getQuantity());
        if (loss) {
            financialAuthorization.requireFinance("post transfer transit loss");
            movement.setName("Transfer transit loss"); movement.setCode("XFER-LOSS-" + UUID.randomUUID().toString().substring(0, 8));
            movement.setUnitCost(value.divide(movement.getQuantity(), 6, RoundingMode.HALF_UP)); movement.setTotalCost(value);
            movement.setStatus("POSTED"); movement.setPostedAt(java.time.LocalDateTime.now()); movement = movements.saveAndFlush(movement);
            costLedger.record(new StockCostLedgerService.RecordCommand(transfer.getCompanyId(), origin.getMaterialId(), warehouseId,
                movement.getId(), null, StockCostMovementType.WRITE_OFF, movement.getQuantity(), movement.getUnitCost(), value,
                null, movement.getReferenceType(), transfer.getId(), transfer.getId(), false, null, null, null, null));
        } else {
            if (returned) movement.setStockBatchId(origin.getSourceStockBatchId());
            else if (movement.getStockBatchId() != null) throw new BusinessException("Receipt batch is derived from the selected shipment");
            movement = stockMovementService.postManagedTransfer(context, movement, origin, value);
        }
        var clearance = new TransferAllocationClearance(); clearance.setName("Transfer " + context.kind());
        clearance.setAllocationId(origin.getId()); clearance.setKind(TransferAllocationClearance.Kind.valueOf(context.kind().name()));
        clearance.setQuantity(movement.getQuantity()); clearance.setBookValue(value); clearance.setPostingDate(movement.getMovementDate());
        clearance.setOperationKey(movement.getId().toString()); clearance.setMovementId(movement.getId());
        clearance.setDestinationStockBatchId(movement.getStockBatchId()); clearance = clearances.saveAndFlush(clearance);
        transit.record(origin.getId(), movement.getId(), context.kind(), movement.getQuantity().negate(), value.negate(), movement.getMovementDate());
        if (loss && warehouseOutbound != null) warehouseOutbound.captureTransitLoss(movement.getId());
        exceptionState.onPhysicalClearance(origin, clearance, movement, context.exceptionCaseId());
        emit(context, origin, movement, movement.getQuantity(), value);
        return result(movement);
    }

    private PostedTransferStock dispatch(TransferPostingContext context, TransferDocument transfer, StockMovement movement) {
        var line = lines.findByIdAndIsDeletedFalse(context.transferLineId()).orElseThrow(() -> new BusinessException("Transfer line not found"));
        requireScope(line);
        var material = materials.findByIdAndIsDeletedFalse(line.getMaterialId())
            .orElseThrow(() -> new BusinessException("Transfer material not found"));
        requireScope(material);
        StockUomPolicy.quantity(material, movement.getQuantity(), false);
        if (!transfer.getId().equals(line.getTransferDocumentId()) || !line.getMaterialId().equals(movement.getMaterialId()))
            throw new BusinessException("Dispatch line does not match the transfer and material");
        if (movement.getStockBatchId() == null) throw new BusinessException("Choose a source batch before dispatch");
        var batch = batches.findByIdAndIsDeletedFalse(movement.getStockBatchId()).orElseThrow(() -> new BusinessException("Source batch not found"));
        requireScope(batch);
        if (context.kind() != TransferPostingContext.Kind.QUARANTINE_RETURN_DISPATCH) {
            var expiry = batch.getExpiresOn() == null ? batch.getExpirationDate() : batch.getExpiresOn();
            if (List.of("INACTIVE", "CANCELLED", "BLOCKED").contains(String.valueOf(batch.getStatus()))
                || (expiry != null && expiry.isBefore(java.time.LocalDate.now())))
                throw new BusinessException("This batch is unavailable. Ask the warehouse manager to review it", "stockPosition.inactive");
        }
        if (!batch.getMaterialId().equals(line.getMaterialId()) || !batch.getWarehouseId().equals(transfer.getSourceWarehouseId()))
            throw new BusinessException("Source batch does not match the material and warehouse");
        if (batch.getSerialNumber() != null && !batch.getSerialNumber().isBlank() && movement.getQuantity().stripTrailingZeros().scale() > 0)
            throw new BusinessException("Serialized stock requires whole quantities");
        var alreadyDispatched = allocations.findByTenantIdAndCompanyIdAndTransferDocumentIdOrderByCreatedAtAscIdAsc(transfer.getTenantId(), transfer.getCompanyId(), transfer.getId())
            .stream().filter(a -> line.getId().equals(a.getTransferLineId())).map(TransferShipmentAllocation::getDispatchedQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (alreadyDispatched.add(movement.getQuantity()).add(line.getCancelledQuantity()).compareTo(line.getPlannedQuantity()) > 0)
            throw new BusinessException("Dispatch exceeds the remaining transfer plan");
        movement.setUnitCost(BigDecimal.ZERO);
        TransferShipmentAllocation quarantineOrigin = null;
        if (context.kind() == TransferPostingContext.Kind.QUARANTINE_RETURN_DISPATCH) {
            quarantineOrigin = allocations.findById(context.allocationId()).orElseThrow(() -> new BusinessException("Quarantine origin is required")); requireScope(quarantineOrigin);
            if (!Objects.equals(transfer.getReturnOfTransferId(), quarantineOrigin.getTransferDocumentId())) throw new BusinessException("Return transfer must link its original transfer");
            UUID batchId = movement.getStockBatchId();
            if (clearances.findByTenantIdAndCompanyIdAndAllocationIdOrderByCreatedAtAscIdAsc(transfer.getTenantId(), transfer.getCompanyId(), quarantineOrigin.getId()).stream()
                .noneMatch(c -> c.getKind() == TransferAllocationClearance.Kind.QUARANTINE_RECEIPT && Objects.equals(c.getDestinationStockBatchId(), batchId)))
                throw new BusinessException("Return batch has no original quarantine receipt");
        }
        movement = stockMovementService.postManagedTransfer(context, movement, quarantineOrigin, null);
        var sourceEvidence = stockAllocations.findByTenantIdAndCompanyIdAndMovementId(transfer.getTenantId(), transfer.getCompanyId(), movement.getId());
        var lineageEvidence = costLineages.findByTenantIdAndCompanyIdAndMovementId(transfer.getTenantId(), transfer.getCompanyId(), movement.getId());
        if (sourceEvidence.isEmpty() || lineageEvidence.isEmpty()) throw new BusinessException("Dispatch requires exact source cost evidence");
        int sequence = 0;
        for (var evidence : sourceEvidence) {
            for (var lineage : lineageEvidence) {
                if (!evidence.getId().equals(lineage.getAllocationId())) continue;
                if (lineage.getCostLayerId() == null) throw new BusinessException("Dispatch source cost layer is missing");
                var allocation = new TransferShipmentAllocation(); allocation.setName("Shipment cost allocation");
                allocation.setShipmentId(context.shipmentId()); allocation.setTransferDocumentId(transfer.getId()); allocation.setTransferLineId(line.getId());
                allocation.setMaterialId(line.getMaterialId()); allocation.setUnitOfMeasureId(evidence.getUomIdSnapshot());
                allocation.setUnitCode(evidence.getUomCodeSnapshot() == null ? "" : evidence.getUomCodeSnapshot()); allocation.setQuantityScale(batch.getSerialNumber() != null && !batch.getSerialNumber().isBlank()
                    ? 0 : StockUomPolicy.quantityScale(material));
                allocation.setSourceWarehouseId(evidence.getSourceWarehouseIdSnapshot()); allocation.setSourceStorageLocationId(evidence.getSourceStorageLocationIdSnapshot());
                allocation.setSourceStorageBinId(evidence.getSourceStorageBinIdSnapshot()); allocation.setSourceStockBatchId(evidence.getSourceStockBatchIdSnapshot());
                allocation.setBatchNumber(batch.getBatchNumber()); allocation.setSerialNumber(batch.getSerialNumber()); allocation.setExpiryDate(batch.getExpirationDate());
                allocation.setDispatchedQuantity(lineage.getQuantityDelta().abs()); allocation.setBookValue(lineage.getValueDelta().abs());
                allocation.setCurrencyCode(lineage.getCurrencyCodeSnapshot() == null ? "UZS" : lineage.getCurrencyCodeSnapshot()); allocation.setBookCurrencyCode("UZS");
                BigDecimal rate = lineage.getExchangeRateSnapshot() == null ? BigDecimal.ONE : lineage.getExchangeRateSnapshot();
                if (rate.signum() <= 0) throw new BusinessException("Dispatch source exchange rate is invalid");
                allocation.setExchangeRate(rate); allocation.setOriginalValue(allocation.getBookValue().divide(rate, 4, RoundingMode.HALF_UP));
                allocation.setSourceMovementAllocationId(evidence.getId()); allocation.setSourceStockMovementId(movement.getId());
                allocation.setConsumedCostLayerIds(List.of(lineage.getCostLayerId())); allocation.setAllocationSequence(++sequence);
                allocation = allocations.saveAndFlush(allocation);
                transit.record(allocation.getId(), movement.getId(), TransferPostingContext.Kind.DISPATCH, allocation.getDispatchedQuantity(), allocation.getBookValue(), movement.getMovementDate());
                emit(context, allocation, movement, allocation.getDispatchedQuantity(), allocation.getBookValue());
            }
        }
        return result(movement);
    }
    private PostedTransferStock result(StockMovement movement) {
        var ids = stockAllocations.findByTenantIdAndCompanyIdAndMovementId(movement.getTenantId(), movement.getCompanyId(), movement.getId())
            .stream().map(StockMovementAllocation::getId).toList();
        return new PostedTransferStock(movement.getId(), ids, movement.getQuantity(), movement.getTotalCost(), movement.getStockBatchId());
    }
    private void emit(TransferPostingContext context, TransferShipmentAllocation allocation, StockMovement movement,
        BigDecimal quantity, BigDecimal value) {
        events.emitIdempotentInCurrentTransaction("INVENTORY.TRANSFER_" + context.kind(), "TransferDocument", context.transferId(), Map.ofEntries(
            Map.entry("transferId", context.transferId()), Map.entry("shipmentId", context.shipmentId()), Map.entry("allocationId", allocation.getId()),
            Map.entry("stockMovementId", movement.getId()), Map.entry("kind", context.kind().name()), Map.entry("quantity", quantity),
            Map.entry("bookValue", value), Map.entry("bookCurrencyCode", allocation.getBookCurrencyCode()),
            Map.entry("originalCurrencyCode", allocation.getCurrencyCode()), Map.entry("exchangeRate", allocation.getExchangeRate()),
            Map.entry("originalValue", value.divide(allocation.getExchangeRate(), 4, RoundingMode.HALF_UP)),
            Map.entry("postingDate", movement.getMovementDate())),
            "transfer:" + context.kind() + ":" + allocation.getId() + ":" + movement.getId());
    }
    void validatePlanSource(UUID warehouseId, UUID materialId, UUID batchId, UUID locationId) {
        validateLocationSelection(warehouseId, locationId, null);
        if (batchId != null) {
            var batch = batches.findByIdAndIsDeletedFalse(batchId).orElseThrow(() -> new BusinessException("Source batch not found")); requireScope(batch);
            if (!warehouseId.equals(batch.getWarehouseId()) || !materialId.equals(batch.getMaterialId()))
                throw new BusinessException("Source batch does not match the planned material and warehouse");
        }
    }
    void validateLocationSelection(UUID warehouseId, UUID locationId, UUID binId) {
        var movement = new StockMovement(); movement.setStorageLocationId(locationId); movement.setStorageBinId(binId); validateLocation(movement, warehouseId);
    }
    private void validateLocation(StockMovement movement, UUID warehouseId) {
        if (movement.getStorageLocationId() != null) {
            var location = locations.findByIdAndIsDeletedFalse(movement.getStorageLocationId()).orElseThrow(() -> new BusinessException("Storage location not found"));
            requireScope(location);
            if (!warehouseId.equals(location.getWarehouseId())) throw new BusinessException("Storage location belongs to another warehouse");
        }
        if (movement.getStorageBinId() != null) {
            var bin = bins.findByIdAndIsDeletedFalse(movement.getStorageBinId()).orElseThrow(() -> new BusinessException("Storage bin not found"));
            requireScope(bin);
            if (!warehouseId.equals(bin.getWarehouseId()) || !Objects.equals(movement.getStorageLocationId(), bin.getStorageLocationId()))
                throw new BusinessException("Storage bin belongs to another location");
        }
    }
    private void requireScope(BusinessEntity entity) {
        if (entity.isDeleted() || !TenantContext.currentTenant().equals(entity.getTenantId()) || !CompanyContext.currentCompany().equals(entity.getCompanyId()))
            throw new BusinessException("Transfer reference is outside the current company");
    }
    static void validateQuantity(BigDecimal value, int scale) {
        if (value == null || value.signum() <= 0 || value.stripTrailingZeros().scale() > Math.min(StockUomPolicy.MAX_QUANTITY_SCALE, scale)
            || value.precision() - value.scale() > 15)
            throw new BusinessException("Quantity must be positive within the stock unit's allowed decimal precision");
    }
}
