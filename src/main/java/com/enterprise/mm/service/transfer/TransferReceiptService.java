package com.enterprise.mm.service.transfer;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.service.StockUomPolicy;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.TransferDocumentResponse;
import com.enterprise.mm.payload.TransferReceiptDtos;
import com.enterprise.mm.payload.TransferReceiveRequest;
import com.enterprise.mm.repository.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Read model and command preparation for the unified receipt table. Posting stays in the transfer facade. */
@Service
@RequiredArgsConstructor
public class TransferReceiptService {
    private final TransferTransitLedgerService transit;
    private final StorageLocationRepository locations;
    private final StorageBinRepository bins;
    private final MaterialRepository materials;

    public record Prepared(TransferReceiveRequest.Line input, TransferShipmentAllocation allocation) {}

    public static String rowKey(UUID shipment, UUID line, UUID batch, String serial) {
        String identity = shipment + ":" + line + ":" + batch + ":" + (serial == null ? "" : serial);
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public TransferReceiptDtos.Context describe(TransferDocumentResponse document) {
        var activeBins = activeBins(document.getDestinationWarehouseId());
        var options = locations.findByTenantIdAndCompanyIdAndWarehouseIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), document.getDestinationWarehouseId()).stream()
            .filter(this::active).map(l -> new TransferReceiptDtos.LocationOption(l.getId(), l.getName(),
                activeBins.stream().anyMatch(b -> l.getId().equals(b.getStorageLocationId())))).toList();
        var defaultLocation = options.stream().filter(l -> l.id().equals(document.getDestinationLocationId())).findFirst().orElse(null);
        boolean canReceive = document.getAllowedActions() != null && document.getAllowedActions().contains("receive");
        String disabled = !canReceive ? "RECEIVE_NOT_ALLOWED" : options.isEmpty() ? "NO_ACTIVE_RECEIPT_LOCATION" : null;
        var lines = new LinkedHashMap<UUID, com.enterprise.mm.payload.TransferDocumentLineResponse>();
        if (document.getLines() != null) document.getLines().forEach(l -> lines.put(l.getId(), l));
        var groups = new LinkedHashMap<String, List<com.enterprise.mm.payload.TransferShipmentDtos.Allocation>>();
        var shipmentIds = new HashMap<String, UUID>();
        if (document.getShipments() != null) for (var shipment : document.getShipments()) for (var a : shipment.allocations()) {
            String key = rowKey(shipment.id(), a.transferLineId(), a.sourceStockBatchId(), a.serialNumber());
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(a); shipmentIds.put(key, shipment.id());
        }
        var rows = new ArrayList<TransferReceiptDtos.Row>();
        groups.forEach((key, allocations) -> {
            var first = allocations.getFirst(); var line = lines.get(first.transferLineId());
            if (line == null) return;
            BigDecimal maximum = allocations.stream().map(a -> nvl(a.unresolvedQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal dispatched = allocations.stream().map(a -> nvl(a.dispatchedQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal received = allocations.stream().filter(a -> a.clearances() != null).flatMap(a -> a.clearances().stream())
                .filter(c -> "GOOD_RECEIPT".equals(c.kind()) || "QUARANTINE_RECEIPT".equals(c.kind()))
                .map(c -> c.quantity()).reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean serial = line.getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED;
            int scale = serial || nonblank(first.serialNumber()) ? 0 : allocations.stream().mapToInt(a -> a.quantityScale()).min().orElse(0);
            String blocker = maximum.signum() <= 0 ? "ALREADY_CLEARED" : disabled;
            rows.add(new TransferReceiptDtos.Row(key, line.getId(), shipmentIds.get(key), first.sourceStockBatchId(),
                line.getMaterialId(), line.getMaterialName(), first.batchNumber(), first.serialNumber(), first.unitCode(), line.getUnitOfMeasure(),
                line.getPlannedQuantity(), dispatched, received, maximum, scale, serial,
                defaultLocation == null ? null : defaultLocation.id(), true,
                defaultLocation != null && defaultLocation.binRequired(), blocker == null, blocker));
        });
        // A combined detail table must retain positions that have not been dispatched yet.
        var dispatchedLines = new HashSet<UUID>();
        rows.forEach(row -> dispatchedLines.add(row.transferLineId()));
        for (var line : lines.values()) if (!dispatchedLines.contains(line.getId())) {
            rows.add(new TransferReceiptDtos.Row(rowKey(null, line.getId(), null, null), line.getId(), null, null,
                line.getMaterialId(), line.getMaterialName(), null, null, null, line.getUnitOfMeasure(),
                line.getPlannedQuantity(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0,
                line.getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED,
                defaultLocation == null ? null : defaultLocation.id(), true,
                defaultLocation != null && defaultLocation.binRequired(), false, "NOT_DISPATCHED"));
        }
        return new TransferReceiptDtos.Context(document.getVersion(), LocalDate.now(), TransferReceiptDtos.ReceiptMode.FULL_SELECTED_ROWS,
            disabled == null, disabled, document.getDestinationWarehouseId(), options, List.copyOf(rows));
    }

    /** Must run under the parent transfer lock, inside the posting transaction. Validates all rows before the first write. */
    public List<Prepared> prepare(TransferDocument transfer, TransferReceiveRequest request, List<TransferShipmentAllocation> all) {
        var groups = new LinkedHashMap<String, List<TransferShipmentAllocation>>();
        var byId = new HashMap<UUID, TransferShipmentAllocation>();
        for (var a : all) {
            groups.computeIfAbsent(rowKey(a.getShipmentId(), a.getTransferLineId(), a.getSourceStockBatchId(), a.getSerialNumber()), ignored -> new ArrayList<>()).add(a);
            byId.put(a.getId(), a);
        }
        var remaining = new HashMap<UUID, BigDecimal>();
        all.forEach(a -> remaining.put(a.getId(), transit.balance(a.getId()).quantity()));
        // Legacy line-only selection must retain its pre-command ambiguity, even when an earlier input consumes a layer.
        var initialRemaining = Map.copyOf(remaining);
        var groupedSelections = new HashSet<UUID>(); var legacySelections = new HashSet<UUID>();
        var usedLocations = new HashSet<String>(); var usedUnits = new HashSet<UUID>();
        var result = new ArrayList<Prepared>();
        for (int index = 0; index < request.getLines().size(); index++) {
            var input = request.getLines().get(index);
            if (input == null) throw error(null, index, "lines", "rowRequired", "Receipt row is required", null);
            boolean grouped = nonblank(input.getRowKey());
            if (input.getRowKey() != null && !grouped) throw error(input, index, "rowKey", "rowInvalid", "Receipt row key is blank", null);
            if (grouped && (input.getShipmentAllocationId() != null || input.getTransferLineId() != null))
                throw error(input, index, "rowKey", "rowInvalid", "Use rowKey without legacy allocation or line identifiers", null);
            List<TransferShipmentAllocation> selected;
            if (grouped) selected = groups.getOrDefault(input.getRowKey(), List.of());
            else if (input.getShipmentAllocationId() != null) selected = byId.containsKey(input.getShipmentAllocationId()) ? List.of(byId.get(input.getShipmentAllocationId())) : List.of();
            else selected = all.stream().filter(a -> Objects.equals(a.getTransferLineId(), input.getTransferLineId()) && initialRemaining.get(a.getId()).signum() > 0).toList();
            if (selected.isEmpty() || (!grouped && selected.size() != 1))
                throw error(input, index, grouped ? "rowKey" : "shipmentAllocationId", "rowInvalid", "Choose an existing receipt row within this transfer", null);
            var first = selected.getFirst();
            if (input.getTransferLineId() != null && !first.getTransferLineId().equals(input.getTransferLineId()))
                throw error(input, index, "transferLineId", "rowInvalid", "Allocation does not belong to the selected transfer line", null);
            for (var a : selected) {
                if (grouped ? groupedSelections.contains(a.getId()) || legacySelections.contains(a.getId()) : groupedSelections.contains(a.getId()))
                    throw error(input, index, "rowKey", "duplicateRow", "An allocation cannot be selected by overlapping receipt rows", null);
                if (!usedLocations.add(a.getId() + ":" + input.getStorageLocationId() + ":" + input.getStorageBinId()))
                    throw error(input, index, "rowKey", "duplicateRow", "Duplicate receipt allocation and location", null);
            }
            if (grouped) selected.forEach(a -> groupedSelections.add(a.getId())); else legacySelections.add(first.getId());
            boolean serial = materials.findByIdAndIsDeletedFalse(first.getMaterialId()).map(m -> m.getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED).orElse(false);
            int scale = serial || selected.stream().anyMatch(a -> nonblank(a.getSerialNumber())) ? 0
                : selected.stream().mapToInt(TransferShipmentAllocation::getQuantityScale).min().orElse(0);
            BigDecimal good = nvl(input.getReceivedQuantity()), damaged = nvl(input.getDamagedQuantity());
            quantity(input, index, "receivedQuantity", good, scale); quantity(input, index, "damagedQuantity", damaged, scale);
            BigDecimal total = good.add(damaged);
            BigDecimal maximum = selected.stream().map(a -> remaining.get(a.getId())).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.signum() == 0) throw error(input, index, "receivedQuantity", "quantityRequired", "Enter a received or quarantined quantity", maximum);
            if (total.compareTo(maximum) > 0) throw error(input, index, "receivedQuantity", "quantityExceeded", "Receipt exceeds unresolved shipment quantity", maximum);
            if (request.getReceiptMode() == TransferReceiptDtos.ReceiptMode.FULL_SELECTED_ROWS && total.compareTo(maximum) != 0)
                throw error(input, index, "damagedQuantity", "fullRowRequired", "Accepted and damaged quantities must equal the entire row remainder", maximum);
            String reason = nonblank(input.getConditionEvidence()) ? input.getConditionEvidence() : input.getShortageReason();
            if (damaged.signum() > 0 && !nonblank(reason)) throw error(input, index, "conditionEvidence", "reasonRequired", "Explain the condition of damaged stock", maximum);
            if (reason != null && reason.length() > 2000) throw error(input, index, "conditionEvidence", "reasonTooLong", "Receipt reason must not exceed 2000 characters", maximum);
            validateLocation(transfer.getDestinationWarehouseId(), input, index, grouped || request.getReceiptMode() == TransferReceiptDtos.ReceiptMode.FULL_SELECTED_ROWS);
            var goodIds = serial ? units(input, index, "materialUnitIds", input.getMaterialUnitIds(), good, usedUnits) : List.<UUID>of();
            var badIds = serial ? units(input, index, "damagedMaterialUnitIds", input.getDamagedMaterialUnitIds(), damaged, usedUnits) : List.<UUID>of();
            int goodOffset = 0, badOffset = 0;
            for (var allocation : selected) {
                BigDecimal capacity = remaining.get(allocation.getId());
                BigDecimal accepted = good.min(capacity); capacity = capacity.subtract(accepted); good = good.subtract(accepted);
                BigDecimal quarantined = damaged.min(capacity); damaged = damaged.subtract(quarantined);
                if (accepted.add(quarantined).signum() == 0) continue;
                var split = new TransferReceiveRequest.Line(); split.setRowKey(input.getRowKey());
                split.setTransferLineId(allocation.getTransferLineId()); split.setShipmentAllocationId(allocation.getId());
                split.setReceivedQuantity(accepted); split.setDamagedQuantity(quarantined);
                split.setStorageLocationId(input.getStorageLocationId()); split.setStorageBinId(input.getStorageBinId());
                split.setConditionEvidence(reason == null ? null : reason.trim()); split.setAttachmentIds(input.getAttachmentIds());
                if (serial) {
                    int goodCount = exactCount(input, index, accepted), badCount = exactCount(input, index, quarantined);
                    split.setMaterialUnitIds(List.copyOf(goodIds.subList(goodOffset, goodOffset + goodCount))); goodOffset += goodCount;
                    split.setDamagedMaterialUnitIds(List.copyOf(badIds.subList(badOffset, badOffset + badCount))); badOffset += badCount;
                }
                remaining.put(allocation.getId(), remaining.get(allocation.getId()).subtract(accepted).subtract(quarantined));
                result.add(new Prepared(split, allocation));
            }
        }
        return List.copyOf(result);
    }

    private void validateLocation(UUID warehouse, TransferReceiveRequest.Line input, int index, boolean required) {
        if (input.getStorageLocationId() == null) {
            if (required || input.getStorageBinId() != null) throw error(input, index, "storageLocationId", "locationRequired", "Select a receiving location", null);
            return;
        }
        var location = locations.findByIdAndIsDeletedFalse(input.getStorageLocationId()).filter(l -> active(l) && warehouse.equals(l.getWarehouseId()))
            .orElseThrow(() -> error(input, index, "storageLocationId", "locationInvalid", "Receiving location is inactive or outside the destination warehouse", null));
        if (input.getStorageBinId() == null) {
            if (required && activeBins(warehouse).stream().anyMatch(b -> location.getId().equals(b.getStorageLocationId())))
                throw error(input, index, "storageBinId", "binRequired", "Select a bin in this location", null);
            return;
        }
        bins.findByIdAndIsDeletedFalse(input.getStorageBinId()).filter(b -> active(b) && warehouse.equals(b.getWarehouseId()) && location.getId().equals(b.getStorageLocationId()))
            .orElseThrow(() -> error(input, index, "storageBinId", "binInvalid", "Receiving bin is inactive or outside the selected location", null));
    }

    private List<StorageBin> activeBins(UUID warehouse) {
        return bins.findByTenantIdAndCompanyIdAndWarehouseIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouse).stream().filter(this::active).toList();
    }
    private boolean active(com.enterprise.mm.common.BusinessEntity entity) {
        return !entity.isDeleted() && "ACTIVE".equals(entity.getStatus()) && TenantContext.currentTenant().equals(entity.getTenantId()) && CompanyContext.currentCompany().equals(entity.getCompanyId());
    }
    private void quantity(TransferReceiveRequest.Line input, int index, String field, BigDecimal value, int scale) {
        if (value.signum() < 0 || value.stripTrailingZeros().scale() > Math.min(scale, StockUomPolicy.MAX_QUANTITY_SCALE) || value.precision() - value.scale() > 15)
            throw error(input, index, field, "quantityInvalid", "Quantity must be nonnegative and fit the unit precision", null);
    }
    private List<UUID> units(TransferReceiveRequest.Line input, int index, String field, List<UUID> ids, BigDecimal quantity, Set<UUID> used) {
        var selected = ids == null ? List.<UUID>of() : ids;
        if (selected.size() != exactCount(input, index, quantity)) throw error(input, index, field, "serialCountMismatch", "Select exactly one physical unit per accepted or damaged item", null);
        for (var id : selected) if (id == null || !used.add(id)) throw error(input, index, field, "serialDuplicate", "A physical unit may be received only once", null);
        return selected;
    }
    private int exactCount(TransferReceiveRequest.Line input, int index, BigDecimal quantity) {
        try { return quantity.intValueExact(); }
        catch (ArithmeticException invalid) { throw error(input, index, "receivedQuantity", "serialCountMismatch", "Serialized receipt requires whole quantities", null); }
    }
    private BusinessException error(TransferReceiveRequest.Line input, int index, String field, String code, String message, BigDecimal maximum) {
        var context = new LinkedHashMap<String, Object>(); context.put("rowIndex", index); context.put("field", field);
        if (input != null) {
            if (input.getRowKey() != null) context.put("rowKey", input.getRowKey());
            if (input.getTransferLineId() != null) context.put("transferLineId", input.getTransferLineId());
            if (input.getShipmentAllocationId() != null) context.put("shipmentAllocationId", input.getShipmentAllocationId());
        }
        if (maximum != null) context.put("maxQuantity", maximum);
        return new BusinessException(message, "transfers.receipt." + code, context);
    }
    private static BigDecimal nvl(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private static boolean nonblank(String value) { return value != null && !value.isBlank(); }
}
