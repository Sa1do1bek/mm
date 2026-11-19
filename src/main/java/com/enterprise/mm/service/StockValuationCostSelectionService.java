package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.payload.StockValuationSelectedStocksResponse;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockValuationCostSelectionService {
    private final StockValuationService valuationService;
    private final InventoryValuationPolicyService policyService;
    private final StockBalanceRepository stockBalanceRepository;
    private final MaterialRepository materialRepository;
    private final WarehouseRepository warehouseRepository;

    public List<StockValuationSelectedStocksResponse.StockCost> calculateStockCosts(UUID warehouseId) {
        return calculateStockCosts(warehouseId, (ValuationMethod) null);
    }

    public List<StockValuationSelectedStocksResponse.StockCost> calculateStockCosts(UUID warehouseId, ValuationMethod valuationMethod) {
        return calculateStockCosts(warehouseId, null, valuationMethod);
    }

    /**
     * TODO item 4 (action-prefill / "Оценка"): the drawer's valuation action needs to
     * open scoped to the single material the operator picked, not the whole warehouse
     * (or whole tenant, if warehouseId is also omitted). Before this overload, callers
     * had no way to ask for just one material's cost row, so the frontend had to fetch
     * every stock cost and filter client-side.
     */
    public List<StockValuationSelectedStocksResponse.StockCost> calculateStockCosts(UUID warehouseId, UUID materialId) {
        return calculateStockCosts(warehouseId, materialId, null);
    }

    public List<StockValuationSelectedStocksResponse.StockCost> calculateStockCosts(
            UUID warehouseId, UUID materialId, ValuationMethod valuationMethod) {
        return findPositiveStockGroups(warehouseId, materialId).stream()
                .map(group -> calculateOne(group, valuationMethod))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<StockGroup> findPositiveStockGroups(UUID warehouseId) {
        return findPositiveStockGroups(warehouseId, null);
    }

    @Transactional(readOnly = true)
    public List<StockGroup> findPositiveStockGroups(UUID warehouseId, UUID materialId) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        Map<UUID, String> materialNames = materialRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(tenantId, companyId).stream()
            .collect(Collectors.toMap(Material::getId, Material::getName, (left, right) -> left));
        Map<UUID, String> warehouseNames = warehouseRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(tenantId, companyId).stream()
            .collect(Collectors.toMap(Warehouse::getId, Warehouse::getName, (left, right) -> left));

        Map<StockKey, StockGroup> stockByKey = new LinkedHashMap<>();
        stockBalanceRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(tenantId, companyId).stream()
                .filter(balance -> warehouseId == null || warehouseId.equals(balance.getWarehouseId()))
                .filter(balance -> materialId == null || materialId.equals(balance.getMaterialId()))
                .forEach(balance -> {
                    StockKey key = new StockKey(balance.getMaterialId(), balance.getWarehouseId());
                    stockByKey.compute(key, (ignored, group) -> group == null
                            ? new StockGroup(
                            balance.getMaterialId(),
                            materialNames.get(balance.getMaterialId()),
                            balance.getWarehouseId(),
                            warehouseNames.get(balance.getWarehouseId()),
                            nvl(balance.getQuantity()))
                            : new StockGroup(
                            group.materialId(),
                            group.materialName(),
                            group.warehouseId(),
                            group.warehouseName(),
                            group.quantity().add(nvl(balance.getQuantity()))));
                });
        return stockByKey.values().stream().filter(group -> group.quantity().signum() > 0).toList();
    }

    @Transactional(readOnly = true)
    public List<StockGroup> findPositiveStockGroupsForWarehouses(List<UUID> warehouseIds) {
        if (warehouseIds == null || warehouseIds.isEmpty()) {
            return List.of();
        }
        Set<UUID> warehouseSet = new java.util.HashSet<>(warehouseIds);
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        Map<UUID, String> materialNames = materialRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(tenantId, companyId).stream()
            .collect(Collectors.toMap(Material::getId, Material::getName, (left, right) -> left));
        Map<UUID, String> warehouseNames = warehouseRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(tenantId, companyId).stream()
            .collect(Collectors.toMap(Warehouse::getId, Warehouse::getName, (left, right) -> left));

        Map<StockKey, StockGroup> stockByKey = new LinkedHashMap<>();
        stockBalanceRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(tenantId, companyId).stream()
                .filter(balance -> warehouseSet.contains(balance.getWarehouseId()))
                .forEach(balance -> {
                    StockKey key = new StockKey(balance.getMaterialId(), balance.getWarehouseId());
                    stockByKey.compute(key, (ignored, group) -> group == null
                            ? new StockGroup(
                            balance.getMaterialId(),
                            materialNames.get(balance.getMaterialId()),
                            balance.getWarehouseId(),
                            warehouseNames.get(balance.getWarehouseId()),
                            nvl(balance.getQuantity()))
                            : new StockGroup(
                            group.materialId(),
                            group.materialName(),
                            group.warehouseId(),
                            group.warehouseName(),
                            group.quantity().add(nvl(balance.getQuantity()))));
                });
        return stockByKey.values().stream().filter(group -> group.quantity().signum() > 0).toList();
    }

    public StockValuationSelectedStocksResponse.StockCost calculateOne(StockGroup group) {
        return calculateOne(group, null);
    }

    public StockValuationSelectedStocksResponse.StockCost calculateOne(StockGroup group, ValuationMethod valuationMethod) {
        ValuationMethod selectedMethod = null;
        try {
            selectedMethod = valuationMethod == null
                ? policyService.resolveMethod(group.materialId(), group.warehouseId())
                : valuationMethod;
            return new StockValuationSelectedStocksResponse.StockCost(
                group.materialId(),
                group.materialName(),
                group.warehouseId(),
                group.warehouseName(),
                group.quantity(),
                selectedMethod,
                calculateCost(selectedMethod, group.materialId(), List.of(group.warehouseId()), null, group.quantity()),
                true,
                null
            );
        } catch (RuntimeException ex) {
            return unavailable(group, ex.getMessage(), selectedMethod);
        }
    }

    private StockValuationSelectedStocksResponse.StockCost unavailable(StockGroup group, String errorMessage, ValuationMethod method) {
        return new StockValuationSelectedStocksResponse.StockCost(
            group.materialId(),
            group.materialName(),
            group.warehouseId(),
            group.warehouseName(),
            group.quantity(),
            method,
            null,
            false,
            errorMessage
        );
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateCost(
        ValuationMethod method,
        UUID materialId,
        List<UUID> warehouseIds,
        UUID stockBatchId,
        BigDecimal quantity
    ) {
        return switch (method) {
            case ACTUAL_COST -> valuationService.calculateActualCost(materialId, warehouseIds, stockBatchId, quantity);
            case SPECIFIC_BATCH -> valuationService.calculateSpecificBatchCost(materialId, warehouseIds, stockBatchId, quantity);
            case FIFO -> valuationService.calculateFifoCost(materialId, warehouseIds, quantity);
            case LIFO -> valuationService.calculateLifoCost(materialId, warehouseIds, quantity);
            case WEIGHTED_AVERAGE -> valuationService.calculateWeightedAverageCost(materialId, warehouseIds, quantity);
            case MOVING_AVERAGE -> valuationService.calculateMovingAverageCost(materialId, warehouseIds, quantity);
            case MARKET_PRICE -> valuationService.calculateMarketPriceCost(materialId, warehouseIds, quantity);
            case LOWER_OF_COST_OR_MARKET -> valuationService.calculateLowerOfCostOrMarketCost(materialId, warehouseIds, quantity);
            case NET_REALIZABLE_VALUE -> valuationService.calculateNetRealizableValueCost(materialId, warehouseIds, quantity);
        };
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record StockKey(UUID materialId, UUID warehouseId) {
    }
}
