package com.enterprise.mm.service;

import static com.enterprise.mm.payload.MaterialCommandViewDtos.*;
import static com.enterprise.mm.payload.MaterialStockDetailDtos.*;

import com.enterprise.mm.entity.StockMovementAllocation;
import com.enterprise.mm.entity.StockMovementAllocationCostLayer;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.repository.InventoryAccountingSettingsRepository;
import com.enterprise.mm.repository.MaterialStockDetailQuery;
import com.enterprise.mm.repository.MaterialStockDetailQuery.LayerRow;
import com.enterprise.mm.repository.MaterialStockDetailQuery.OriginEvidence;
import com.enterprise.mm.service.MaterialCommandViewScopeGuard.MaterialScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MaterialStockDetailService {
    private static final int MAX_ANCESTRY_DEPTH = 64;
    private final MaterialCommandViewScopeGuard scopeGuard;
    private final MaterialCommandViewAccessPolicy accessPolicy;
    private final MaterialStockDetailQuery query;
    private final InventoryAccountingSettingsRepository settingsRepository;
    private final Clock materialCommandViewClock;

    public Detail detail(UUID materialId, Filter requested, int page, int size) {
        accessPolicy.requireReadable(SectionKey.INVENTORY);
        Pageable pageable = pageable(page, size);
        MaterialScope scope = scopeGuard.requireGoods(materialId, requested.warehouseId());
        LocalDate today = LocalDate.now(materialCommandViewClock);
        LocalDate asOf = requested.asOf() == null ? today : requested.asOf();
        if (asOf.isAfter(today)) throw new BadRequestException("Stock detail date cannot be in the future");
        boolean historical = asOf.isBefore(today);
        Filter filter = new Filter(scope.warehouseId(), requested.stockBatchId(), asOf, requested.positiveOnly());
        Permissions permissions = permissions();
        var balances = query.balances(scope, filter, historical);
        var totals = query.layerTotals(scope, filter, historical);
        String currency = settingsRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(scope.tenantId(), scope.companyId())
            .map(settings -> settings.getBaseCurrencyCode()).orElse(null);
        List<String> warnings = new ArrayList<>();
        // A current balance or revalued layer that cannot be reconciled is never relabelled as historical stock.
        boolean available = !historical || (balances.invalidHistoryRows() == 0 && totals.invalidHistoryRows() == 0
            && !query.hasUnallocatedHistory(scope, filter));
        if (!available) warnings.add("HISTORICAL_EVIDENCE_INCOMPLETE");
        if (historical && available) warnings.add("RECONSTRUCTED_FROM_RECORDED_MOVEMENT_DATES");
        warnings.add("ORIGIN_QUANTITIES_FOLLOW_COST_ALLOCATION_POLICY");
        boolean layerCurrencyMatches = currency != null && (totals.minCurrency() == null
            || (currency.equals(totals.minCurrency()) && currency.equals(totals.maxCurrency())));
        if (!layerCurrencyMatches) warnings.add("COST_LAYER_BASE_CURRENCY_UNAVAILABLE");
        BigDecimal difference = balances.quantity().subtract(totals.quantity());
        if (available && difference.signum() != 0) warnings.add("STOCK_AND_COST_LAYER_QUANTITIES_DIFFER");
        if (available && layerCurrencyMatches && balances.value().compareTo(totals.value()) != 0)
            warnings.add("STOCK_AND_COST_LAYER_VALUES_DIFFER");

        Summary summary = new Summary(metric(balances.quantity(), available), metric(balances.available(), available),
            metric(balances.reserved(), available), metric(balances.quarantine(), available),
            money(balances.value(), currency, permissions.canViewCost(), available),
            metric(totals.quantity(), available), metric(difference.max(BigDecimal.ZERO), available),
            metric(difference, available), money(totals.value(), currency, permissions.canViewCost(), available && layerCurrencyMatches),
            money(balances.value().subtract(totals.value()), currency, permissions.canViewCost(), available && layerCurrencyMatches));
        Page<Origin> origins = Page.empty(pageable);
        if (available) {
            var layers = query.layers(scope, filter, historical, pageable);
            Map<UUID, OriginEvidence> ancestry = ancestry(scope, layers.getContent().stream().map(LayerRow::id).toList());
            origins = layers.map(row -> origin(row, ancestry, permissions));
        }
        var m = scope.material();
        return new Detail(new MaterialInfo(m.getId(), m.getCode(), m.getName(), m.getUnitOfMeasureId()).withUnitShortName(com.enterprise.mm.common.UnitOfMeasureLabels.materialShortName(m, org.springframework.context.i18n.LocaleContextHolder.getLocale())),
            scope.warehouseId(), filter.stockBatchId(), asOf, materialCommandViewClock.instant(), historical,
            available ? DataStatus.AVAILABLE : DataStatus.UNAVAILABLE, permissions, m.getValuationMethod(),
            QuantityBasis.COST_LAYER_ALLOCATION, summary, PageResponse.from(origins), List.copyOf(warnings));
    }

    private Map<UUID, OriginEvidence> ancestry(MaterialScope scope, List<UUID> ids) {
        Map<UUID, OriginEvidence> found = new HashMap<>();
        Set<UUID> attempted = new HashSet<>();
        Set<UUID> next = new HashSet<>(ids);
        for (int depth = 0; depth < MAX_ANCESTRY_DEPTH && !next.isEmpty(); depth++) {
            attempted.addAll(next);
            var rows = query.origins(scope, next);
            rows.forEach(row -> found.put(row.id(), row));
            next = rows.stream().map(OriginEvidence::parentId).filter(id -> id != null && !attempted.contains(id))
                .collect(Collectors.toSet());
        }
        return found;
    }

    private Origin origin(LayerRow row, Map<UUID, OriginEvidence> ancestry, Permissions permissions) {
        OriginEvidence local = ancestry.get(row.id());
        OriginEvidence root = local;
        Set<UUID> visited = new HashSet<>();
        boolean complete = root != null;
        while (root != null && root.parentId() != null) {
            if (!visited.add(root.id())) { complete = false; break; }
            root = ancestry.get(root.parentId());
            if (root == null) complete = false;
        }
        boolean receipt = complete && root != null && root.posted() && "RECEIPT".equals(root.layerType());
        ProvenanceStatus status = receipt && root.verifiedReceipt() && root.supplierId() != null ? ProvenanceStatus.VERIFIED
            : receipt ? ProvenanceStatus.PARTIAL : ProvenanceStatus.UNKNOWN;
        // A transfer without a complete parent chain must not masquerade as an original receipt.
        OriginEvidence original = complete ? root : null;
        Party supplier = original != null && permissions.canViewSupplier() && original.supplierId() != null
            ? new Party(original.supplierId(), original.supplierCode(), original.supplierName()) : null;
        Party source = local != null && local.sourceWarehouseId() != null
            ? new Party(local.sourceWarehouseId(), local.sourceWarehouseCode(), local.sourceWarehouseName()) : null;
        Document document = original != null && permissions.canViewDocuments() && original.referenceId() != null
            ? new Document(original.referenceType(), original.referenceId(), original.documentCode()) : null;
        BigDecimal unitCost = row.remaining().signum() == 0 ? null
            : row.value().divide(row.remaining(), 6, RoundingMode.HALF_UP);
        return new Origin(row.id(), row.warehouseId(), row.warehouseName(), row.batchId(), row.batchNumber(),
            row.layerType(), decimal(row.received()), decimal(row.remaining()), local == null ? null : local.receivedDate(),
            receipt ? original.receivedDate() : null,
            original != null && permissions.canViewDocuments() ? original.movementId() : null, source, supplier, document,
            money(receipt ? original.purchaseUnitPrice() : null, original == null ? null : original.purchaseCurrency(),
                permissions.canViewCost(), receipt),
            receipt && permissions.canViewCost() ? decimal(original.purchaseExchangeRate()) : null,
            money(receipt ? original.receiptUnitCost() : null, original == null ? null : original.currency(),
                permissions.canViewCost(), receipt),
            money(unitCost, row.currency(), permissions.canViewCost(), true),
            money(row.value(), row.currency(), permissions.canViewCost(), true), status, QuantityBasis.COST_LAYER_ALLOCATION);
    }

    public MovementDetail movement(UUID materialId, UUID movementId, UUID warehouseId, int page, int size) {
        accessPolicy.requireReadable(SectionKey.INVENTORY);
        var pageable = pageable(page, size);
        var scope = scopeGuard.requireGoods(materialId, warehouseId);
        var movement = query.movement(scope, movementId);
        if (movement == null) throw new NotFoundException("Stock movement not found: " + movementId);
        var rows = query.allocations(scope, movementId, pageable);
        if (scope.warehouseId() != null && rows.getTotalElements() == 0)
            throw new NotFoundException("Stock movement allocation not found in warehouse: " + movementId);
        var permissions = permissions();
        var costs = query.costAllocations(scope, rows.getContent().stream().map(StockMovementAllocation::getId).toList())
            .stream().collect(Collectors.groupingBy(StockMovementAllocationCostLayer::getAllocationId));
        var allocations = rows.map(a -> allocation(a, costs.getOrDefault(a.getId(), List.of()), permissions));
        return new MovementDetail(movement.id(), movement.materialId(), movement.status(), decimal(movement.quantity()),
            permissions, rows.getTotalElements() == 0 ? DataStatus.UNAVAILABLE : DataStatus.AVAILABLE,
            PageResponse.from(allocations));
    }

    private Allocation allocation(StockMovementAllocation a, List<StockMovementAllocationCostLayer> costs, Permissions p) {
        boolean source = "SOURCE".equals(a.getAllocationRole());
        var mappedCosts = costs.stream().map(c -> new CostAllocation(c.getCostLayerId(), c.getParentCostLayerIdSnapshot(),
            c.getLineageRole(), decimal(c.getQuantityDelta()), money(c.getValueDelta(), c.getCurrencyCodeSnapshot(), p.canViewCost(), true),
            money(c.getUnitCostSnapshot(), c.getCurrencyCodeSnapshot(), p.canViewCost(), true))).toList();
        return new Allocation(a.getId(), a.getAllocationSequence(), a.getAllocationRole(),
            source ? a.getSourceWarehouseIdSnapshot() : a.getTargetWarehouseIdSnapshot(),
            source ? a.getSourceStockBatchIdSnapshot() : a.getTargetStockBatchIdSnapshot(),
            source ? a.getSourceBatchNumberSnapshot() : a.getTargetBatchNumberSnapshot(),
            source ? a.getSourceStorageLocationIdSnapshot() : a.getTargetStorageLocationIdSnapshot(),
            source ? a.getSourceStorageBinIdSnapshot() : a.getTargetStorageBinIdSnapshot(),
            decimal(a.getOnHandDelta()), decimal(a.getAvailableDelta()), decimal(a.getReservedDelta()), decimal(a.getQuarantineDelta()),
            money(a.getTotalValueDelta(), a.getCurrencyCodeSnapshot(), p.canViewCost(), true),
            money(a.getUnitCostSnapshot(), a.getCurrencyCodeSnapshot(), p.canViewCost(), true), mappedCosts);
    }

    private Permissions permissions() {
        return new Permissions(accessPolicy.sectionVisibility(SectionKey.VALUATION) == Visibility.READ,
            accessPolicy.sectionVisibility(SectionKey.SUPPLIERS) == Visibility.READ,
            accessPolicy.sectionVisibility(SectionKey.DOCUMENTS) == Visibility.READ);
    }

    private Pageable pageable(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new BadRequestException("Page must be nonnegative and size between 1 and 100");
        return PageRequest.of(page, size);
    }

    private static DecimalMetric metric(BigDecimal amount, boolean available) {
        return available ? DecimalMetric.available(amount) : DecimalMetric.noData();
    }

    private static MoneyMetric money(BigDecimal amount, String currency, boolean allowed, boolean available) {
        if (!allowed) return MoneyMetric.denied();
        return available && amount != null && currency != null ? MoneyMetric.available(amount, currency) : MoneyMetric.noData();
    }

    private static String decimal(BigDecimal value) { return value == null ? null : value.toPlainString(); }
}
