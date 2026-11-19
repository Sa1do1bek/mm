package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialUnit;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.TransferDocument;
import com.enterprise.mm.entity.TransferDocumentLine;
import com.enterprise.mm.enums.PickingMethod;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.enums.StockAllocationMode;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.TransferDispatchRequest;
import com.enterprise.mm.payload.TransferPickingDtos.*;
import com.enterprise.mm.payload.WarehouseStockFactRequest;
import com.enterprise.mm.payload.WarehouseStockFactPickingDtos;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.MaterialUnitRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Used within the caller's authorized warehouse transaction. Preview never locks or mutates stock. */
@Service
@RequiredArgsConstructor
public class StockPickingAllocationService {
    private final StockBalanceRepository balances;
    private final MaterialRepository materials;
    private final MaterialUnitRepository units;
    private final ExactOpeningImportGuard openingImportGuard;
    private final InventoryCostingService costing;
    private final com.enterprise.mm.security.pilot.PilotCapabilityPolicy capabilities;

    public record Plan(List<TransferDispatchRequest.Line> postings, List<Decision> decisions) {}

    public Context context(TransferDocument transfer, List<TransferDocumentLine> lines) {
        Pool pool = load(transfer, lines, false);
        return new Context(transfer.getId(), transfer.getVersion(), LocalDateTime.now(), lines.stream().map(line -> {
            Material material = pool.materials.get(line.getMaterialId());
            BigDecimal available = pool.positions.stream().filter(b -> b.getMaterialId().equals(line.getMaterialId()))
                .map(pool::executable).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new LineContext(line.getId(), material.getId(), material.getName(), material.getUnitOfMeasureId(),
                material.getUnitOfMeasure().getCode(), StockUomPolicy.quantityScale(material), remaining(line),
                available, available.min(remaining(line)), PickingMethod.FIFO).withUnitShortName(com.enterprise.mm.common.UnitOfMeasureLabels.localizedShort(material.getUnitOfMeasure(), org.springframework.context.i18n.LocaleContextHolder.getLocale()));
        }).toList());
    }

    public StockOptions options(TransferDocument transfer, TransferDocumentLine line, PickingMethod method, int page, int size) {
        Pool pool = load(transfer, List.of(line), false);
        return new StockOptions(line.getId(), method, page, size, pool.positions.size(), options(pool, method, page, size));
    }

    public WarehouseStockFactPickingDtos.Options issueOptions(UUID warehouseId, UUID materialId, PickingMethod method, int page, int size) {
        Pool pool = load(source(warehouseId), List.of(materialId), false);
        return issueOptions(pool, materialId, method, page, size);
    }

    public WarehouseStockFactPickingDtos.Options fixedAssetIssueOptions(UUID warehouseId, UUID materialId, PickingMethod method, int page, int size) {
        return issueOptions(load(source(warehouseId), List.of(materialId), false, MaterialType.FIXED_ASSET), materialId, method, page, size);
    }

    private WarehouseStockFactPickingDtos.Options issueOptions(Pool pool, UUID materialId, PickingMethod method, int page, int size) {
        BigDecimal available = pool.positions.stream().map(pool::executable).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new WarehouseStockFactPickingDtos.Options(materialId, method,
            pool.type == MaterialType.FIXED_ASSET ? 0 : StockUomPolicy.quantityScale(pool.materials.get(materialId)),
            available, page, size, pool.positions.size(), options(pool, method, page, size));
    }

    private List<Position> options(Pool pool, PickingMethod method, int page, int size) {
        if (page < 0 || size < 1 || size > 200) throw new BadRequestException("Use page >= 0 and size between 1 and 200");
        List<StockBalance> sorted = pool.positions.stream().sorted(StockPickingOrder.balances(method)).toList();
        return sorted.stream().skip((long) page * size).limit(size).map(b -> {
            String reason = pool.blockedReason(b);
            // Corrupt cross-scope references must not expose another company's batch metadata.
            var batch = "scope".equals(reason) ? null : b.getStockBatch();
            BigDecimal executable = pool.executable(b);
            if (reason == null && executable.signum() <= 0) reason = "exhausted";
            return new Position(b.getId(), b.getStockBatchId(), batch == null ? null : batch.getBatchNumber(),
                b.getStorageLocationId(), b.getStorageBinId(), batch == null ? null : batch.getReceivedDate(),
                batch == null ? null : (batch.getExpiresOn() == null ? batch.getExpirationDate() : batch.getExpiresOn()),
                nvl(b.getQuantity()), nvl(b.getAvailableQuantity()), executable, reason == null, reason,
                reason == null ? pool.matchingUnits(b).stream().map(u -> new SerialUnit(u.getId(), u.getManufacturerSerialNumber())).toList() : List.of());
        }).toList();
    }

    public List<WarehouseStockFactPickingDtos.Line> allocateIssue(UUID warehouseId, List<WarehouseStockFactRequest.Line> lines, boolean lock) {
        return allocateIssue(warehouseId, lines, lock, MaterialType.TMB);
    }

    public List<WarehouseStockFactPickingDtos.Line> allocateFixedAssetIssue(UUID warehouseId, List<WarehouseStockFactRequest.Line> lines, boolean lock) {
        return allocateIssue(warehouseId, lines, lock, MaterialType.FIXED_ASSET);
    }

    private List<WarehouseStockFactPickingDtos.Line> allocateIssue(UUID warehouseId, List<WarehouseStockFactRequest.Line> lines, boolean lock, MaterialType type) {
        if (lines == null || lines.isEmpty() || lines.size() > 100 || lines.stream().anyMatch(l -> l == null || l.materialId() == null))
            throw new BadRequestException("Select between one and one hundred materials");
        if (lines.stream().map(WarehouseStockFactRequest.Line::materialId).distinct().count() != lines.size())
            throw new BadRequestException("Combine duplicate materials into one line");
        Pool pool = load(source(warehouseId), lines.stream().map(WarehouseStockFactRequest.Line::materialId).sorted().toList(), lock, type);
        List<WarehouseStockFactPickingDtos.Line> result = new ArrayList<>();
        for (var line : lines) {
            StockUomPolicy.quantity(pool.materials.get(line.materialId()), line.quantity(), false);
            if (type == MaterialType.FIXED_ASSET && line.quantity().stripTrailingZeros().scale() > 0)
                throw new BadRequestException("Fixed asset quantity must be whole");
            PickingMethod method = line.pickingMethod() == null ? PickingMethod.FIFO : line.pickingMethod();
            StockAllocationMode mode = line.allocationMode() == null ? StockAllocationMode.AUTO : line.allocationMode();
            if (line.stockPositionId() != null || (line.materialUnitIds() != null && !line.materialUnitIds().isEmpty()))
                throw new BadRequestException("Use allocations for manual batch selection");
            List<SelectedAllocation> selected;
            if (mode == StockAllocationMode.AUTO) {
                if (line.allocations() != null && !line.allocations().isEmpty())
                    throw new BadRequestException("AUTO allocation does not accept manual allocations");
                selected = automatic(pool, line.materialId(), line.quantity(), method);
            } else {
                if (line.allocations() == null || line.allocations().isEmpty())
                    throw new BadRequestException("MANUAL allocation requires source quantities");
                selected = new ArrayList<>();
                Set<UUID> seen = new HashSet<>();
                BigDecimal total = BigDecimal.ZERO;
                for (var allocation : line.allocations()) {
                    if (allocation == null || allocation.stockBalanceId() == null || !seen.add(allocation.stockBalanceId()))
                        throw new BadRequestException("Choose each stock position only once");
                    StockUomPolicy.quantity(pool.materials.get(line.materialId()), allocation.quantity(), false);
                    if (type == MaterialType.FIXED_ASSET && allocation.quantity().stripTrailingZeros().scale() > 0)
                        throw new BadRequestException("Fixed asset allocation quantity must be whole");
                    var position = pool.positions.stream().filter(b -> b.getId().equals(allocation.stockBalanceId())
                        && b.getMaterialId().equals(line.materialId())).findFirst()
                        .orElseThrow(() -> new BadRequestException("Stock position is outside the selected material or warehouse"));
                    selected.add(pool.take(position, allocation.quantity(), allocation.materialUnitIds(), false, method));
                    total = total.add(allocation.quantity());
                }
                if (total.compareTo(line.quantity()) != 0) throw new BadRequestException("Manual allocation sum must equal the line quantity");
            }
            result.add(new WarehouseStockFactPickingDtos.Line(line.materialId(), method, mode, line.quantity(), List.copyOf(selected)));
        }
        return List.copyOf(result);
    }

    private List<SelectedAllocation> automatic(Pool pool, UUID materialId, BigDecimal quantity, PickingMethod method) {
        List<SelectedAllocation> selected = new ArrayList<>();
        BigDecimal left = quantity;
        for (var position : pool.positions.stream().filter(b -> b.getMaterialId().equals(materialId)).sorted(StockPickingOrder.balances(method)).toList()) {
            BigDecimal take = pool.executable(position).min(left);
            if (take.signum() > 0) selected.add(pool.take(position, take, null, true, method));
            left = left.subtract(take);
            if (left.signum() == 0) break;
        }
        if (left.signum() > 0) throw new BusinessException("Insufficient executable stock for requested quantity", "stockPicking.insufficient");
        return selected;
    }

    public Plan allocate(TransferDocument transfer, List<TransferDocumentLine> lines, TransferDispatchRequest request, boolean lock) {
        if (request == null || request.getLines() == null || request.getLines().isEmpty())
            throw new BadRequestException("Select at least one transfer line");
        Map<UUID, TransferDocumentLine> byLine = new HashMap<>();
        lines.forEach(line -> byLine.put(line.getId(), line));
        // Only load materials named by the command, but validate line ownership before querying stock.
        for (var input : request.getLines()) {
            if (input == null || !byLine.containsKey(input.getTransferLineId()))
                throw new BadRequestException("transferLineId does not belong to transfer");
        }
        List<TransferDocumentLine> selected = request.getLines().stream().map(i -> byLine.get(i.getTransferLineId())).distinct().toList();
        Pool pool = load(transfer, selected, lock);
        Map<UUID, BigDecimal> totals = new HashMap<>();
        Map<UUID, Boolean> modernLines = new HashMap<>();
        Set<String> sourceSelections = new HashSet<>();
        List<TransferDispatchRequest.Line> postings = new ArrayList<>();
        List<Decision> decisions = new ArrayList<>();
        for (var input : request.getLines()) {
            var line = byLine.get(input.getTransferLineId());
            Material material = pool.materials.get(line.getMaterialId());
            BigDecimal quantity = input.getDispatchedQuantity();
            StockUomPolicy.quantity(material, quantity, false);
            totals.merge(line.getId(), quantity, BigDecimal::add);
            if (totals.get(line.getId()).compareTo(remaining(line)) > 0)
                throw new BadRequestException("Selected quantity exceeds the remaining transfer plan");
            boolean modern = input.getAllocationMode() != null;
            Boolean previous = modernLines.putIfAbsent(line.getId(), modern);
            if (previous != null && (modern || previous))
                throw new BadRequestException("Send one AUTO/MANUAL row per transfer line; do not mix request formats");
            PickingMethod method = input.getPickingMethod() == null ? PickingMethod.FIFO : input.getPickingMethod();
            StockAllocationMode mode = modern ? input.getAllocationMode() : StockAllocationMode.MANUAL;
            List<SelectedAllocation> allocations = new ArrayList<>();
            if (!modern) {
                if (input.getStockBatchId() == null || input.getAllocations() != null)
                    throw new BadRequestException("Choose a source batch or an explicit allocationMode");
                List<StockBalance> matches = pool.positions.stream().filter(b -> b.getMaterialId().equals(line.getMaterialId())
                    && Objects.equals(b.getStockBatchId(), input.getStockBatchId())
                    && Objects.equals(b.getStorageLocationId(), input.getStorageLocationId())
                    && Objects.equals(b.getStorageBinId(), input.getStorageBinId())
                    && b.getOwnerPartyId() == null && "OWNED".equals(b.getStockCategory())).toList();
                if (matches.size() != 1) throw new BadRequestException("Source stock position not found or ambiguous");
                allocations.add(pool.take(matches.getFirst(), quantity, input.getMaterialUnitIds(), false, method));
            } else {
                if (input.getStockBatchId() != null || input.getStorageLocationId() != null || input.getStorageBinId() != null
                    || input.getMaterialUnitIds() != null)
                    throw new BadRequestException("Use allocations for MANUAL selection; do not mix legacy source fields");
                if (mode == StockAllocationMode.AUTO) {
                    if (input.getAllocations() != null && !input.getAllocations().isEmpty())
                        throw new BadRequestException("AUTO allocation does not accept manual allocations");
                    allocations.addAll(automatic(pool, line.getMaterialId(), quantity, method));
                } else {
                    if (input.getAllocations() == null || input.getAllocations().isEmpty())
                        throw new BadRequestException("MANUAL allocation requires source quantities");
                    BigDecimal sum = BigDecimal.ZERO;
                    for (var allocation : input.getAllocations()) {
                        if (allocation == null || allocation.getStockBalanceId() == null)
                            throw new BadRequestException("stockBalanceId is required");
                        StockUomPolicy.quantity(material, allocation.getQuantity(), false);
                        var b = pool.positions.stream().filter(p -> p.getId().equals(allocation.getStockBalanceId())
                            && p.getMaterialId().equals(line.getMaterialId())).findFirst()
                            .orElseThrow(() -> new BadRequestException("Source stock position is outside this transfer line"));
                        allocations.add(pool.take(b, allocation.getQuantity(), allocation.getMaterialUnitIds(), false, method));
                        sum = sum.add(allocation.getQuantity());
                    }
                    if (sum.compareTo(quantity) != 0) throw new BadRequestException("Manual allocation sum must equal the line quantity");
                }
            }
            for (var allocation : allocations) {
                if (!sourceSelections.add(line.getId() + ":" + allocation.stockBalanceId()))
                    throw new BadRequestException("Duplicate source allocation");
                var posting = new TransferDispatchRequest.Line();
                posting.setTransferLineId(line.getId()); posting.setDispatchedQuantity(allocation.quantity());
                posting.setStockBatchId(allocation.stockBatchId()); posting.setStorageLocationId(allocation.storageLocationId());
                posting.setStorageBinId(allocation.storageBinId()); posting.setMaterialUnitIds(allocation.materialUnitIds());
                postings.add(posting);
            }
            decisions.add(new Decision(line.getId(), method, mode, quantity, List.copyOf(allocations)));
        }
        return new Plan(List.copyOf(postings), List.copyOf(decisions));
    }

    private Pool load(TransferDocument transfer, List<TransferDocumentLine> lines, boolean lock) {
        if (!TenantContext.currentTenant().equals(transfer.getTenantId()) || !CompanyContext.currentCompany().equals(transfer.getCompanyId()))
            throw new BadRequestException("Transfer is outside current company scope");
        return load(new Source(transfer.getTenantId(), transfer.getCompanyId(), transfer.getSourceWarehouseId()),
            lines.stream().map(TransferDocumentLine::getMaterialId).distinct().sorted().toList(), lock);
    }

    private record Source(String tenantId, UUID companyId, UUID warehouseId) {}
    private static Source source(UUID warehouseId) { return new Source(TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouseId); }

    private Pool load(Source source, List<UUID> ids, boolean lock) {
        return load(source, ids, lock, MaterialType.TMB);
    }

    private Pool load(Source source, List<UUID> ids, boolean lock, MaterialType type) {
        Map<UUID, Material> materialMap = new HashMap<>();
        for (UUID id : ids) {
            Material material = materials.findByIdAndIsDeletedFalse(id)
                .filter(m -> source.tenantId().equals(m.getTenantId()) && source.companyId().equals(m.getCompanyId()))
                .orElseThrow(() -> new BadRequestException("Transfer material not found"));
            StockUomPolicy.quantityScale(material);
            if (type == MaterialType.FIXED_ASSET && material.getMaterialType() != type)
                throw new BadRequestException("Select a fixed-asset material");
            materialMap.put(id, material);
        }
        if (ids.isEmpty()) return new Pool(source, List.of(), materialMap, List.of(), serialEnabled(), type);
        // Join the company import lock before taking balance locks, as stock posting does.
        if (lock) {
            openingImportGuard.assertOperationsAllowed(source.companyId());
            // Ordinary issues lock cost layers before balances. Taking balances first here
            // creates a cycle when a transfer and an issue touch the same source stock.
            costing.lockPickingSources(source.warehouseId(), ids);
        }
        var rows = lock ? balances.lockTransferPickingBalances(source.tenantId(), source.companyId(), source.warehouseId(), ids)
            : balances.findTransferPickingBalances(source.tenantId(), source.companyId(), source.warehouseId(), ids);
        var serialIds = ids.stream().filter(id -> type == MaterialType.TMB && materialMap.get(id).getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED).toList();
        var serialUnits = serialIds.isEmpty() ? List.<MaterialUnit>of()
            : units.findTransferPickingUnits(source.tenantId(), source.companyId(), source.warehouseId(), serialIds);
        return new Pool(source, rows, materialMap, serialUnits, serialEnabled(), type);
    }

    private boolean serialEnabled() {
        return capabilities.enabled(com.enterprise.mm.security.pilot.PilotCapability.SERIALIZED_EXECUTION);
    }

    private static BigDecimal remaining(TransferDocumentLine line) {
        return nvl(line.getPlannedQuantity()).subtract(nvl(line.getDispatchedQuantity())).subtract(nvl(line.getCancelledQuantity())).max(BigDecimal.ZERO);
    }
    private static BigDecimal nvl(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    private static final class Pool {
        final Source source;
        final List<StockBalance> positions;
        final Map<UUID, Material> materials;
        final List<MaterialUnit> units;
        final Map<UUID, BigDecimal> consumed = new HashMap<>();
        final Set<UUID> usedUnits = new HashSet<>();
        final boolean serialEnabled;
        final MaterialType type;
        Pool(Source source, List<StockBalance> positions, Map<UUID, Material> materials, List<MaterialUnit> units, boolean serialEnabled, MaterialType type) {
            this.source = source; this.positions = positions; this.materials = materials; this.units = units; this.serialEnabled = serialEnabled;
            this.type = type;
        }
        boolean serialized(Material material) { return type == MaterialType.TMB && material.getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED; }
        String blockedReason(StockBalance b) {
            if (!source.tenantId().equals(b.getTenantId()) || !source.companyId().equals(b.getCompanyId())
                || !source.warehouseId().equals(b.getWarehouseId())) return "scope";
            if (b.getOwnerPartyId() != null || !"OWNED".equals(b.getStockCategory())) return "ownership";
            Material material = materials.get(b.getMaterialId());
            if (material == null || material.getMaterialType() != type
                || material.getMaterialSubtype() == com.enterprise.mm.enums.MaterialSubtype.RETURNABLE_PACKAGING) return "capability";
            if (serialized(material) && !serialEnabled) return "capability";
            var batch = b.getStockBatch();
            if (batch != null && (!source.tenantId().equals(batch.getTenantId()) || !source.companyId().equals(batch.getCompanyId()))) return "scope";
            return StockPositionPolicy.blockedReason(b, true);
        }
        List<MaterialUnit> matchingUnits(StockBalance b) {
            return units.stream().filter(u -> !usedUnits.contains(u.getId()) && u.getMaterialId().equals(b.getMaterialId())
                && Objects.equals(u.getStockBatchId(), b.getStockBatchId())
                && Objects.equals(u.getStorageLocationId(), b.getStorageLocationId())
                && Objects.equals(u.getStorageBinId(), b.getStorageBinId())).toList();
        }
        BigDecimal executable(StockBalance b) {
            if (blockedReason(b) != null) return BigDecimal.ZERO;
            BigDecimal left = nvl(b.getAvailableQuantity()).min(nvl(b.getQuantity()))
                .subtract(consumed.getOrDefault(b.getId(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
            Material material = materials.get(b.getMaterialId());
            if (serialized(material))
                left = left.min(BigDecimal.valueOf(matchingUnits(b).size()));
            return left.setScale(type == MaterialType.FIXED_ASSET ? 0 : StockUomPolicy.quantityScale(material), RoundingMode.DOWN);
        }
        SelectedAllocation take(StockBalance b, BigDecimal quantity, List<UUID> selectedUnits, boolean auto, PickingMethod method) {
            if (blockedReason(b) != null) throw new BusinessException("Selected stock position is unavailable", "stockPicking.unavailable");
            if (quantity.compareTo(executable(b)) > 0)
                throw new BusinessException("Selected quantity exceeds executable source stock", "stockPicking.insufficient");
            List<UUID> unitIds = List.of();
            if (serialized(materials.get(b.getMaterialId()))) {
                var eligible = new ArrayList<>(matchingUnits(b));
                if (method == PickingMethod.LIFO) Collections.reverse(eligible);
                int count = quantity.intValueExact();
                if (auto) unitIds = eligible.stream().limit(count).map(MaterialUnit::getId).toList();
                else {
                    if (selectedUnits == null || selectedUnits.size() != count || new HashSet<>(selectedUnits).size() != count)
                        throw new BadRequestException("Choose exactly one distinct material unit per whole quantity");
                    Set<UUID> allowed = new HashSet<>(eligible.stream().map(MaterialUnit::getId).toList());
                    if (!allowed.containsAll(selectedUnits)) throw new BadRequestException("Material unit does not belong to the selected available position");
                    unitIds = List.copyOf(selectedUnits);
                }
                usedUnits.addAll(unitIds);
            } else if (selectedUnits != null && !selectedUnits.isEmpty()) {
                throw new BadRequestException("Material unit selection requires serial tracking");
            }
            consumed.merge(b.getId(), quantity, BigDecimal::add);
            return new SelectedAllocation(b.getId(), b.getStockBatchId(), b.getStorageLocationId(), b.getStorageBinId(), quantity, unitIds);
        }
    }
}
