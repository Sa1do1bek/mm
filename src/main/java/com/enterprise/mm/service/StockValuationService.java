package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockBatch;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.StockValuation;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockBatchRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.StockValuationRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockValuationService {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private ExactOpeningImportGuard openingImportGuard;
    private final StockBatchRepository stockBatchRepository;
    private final StockBalanceRepository stockBalanceRepository;
    private final StockValuationRepository stockValuationRepository;
    private final StockMovementRepository stockMovementRepository;
    private final MaterialRepository materialRepository;
    private final MaterialMarketPriceService materialMarketPriceService;
    private final AuditService auditService;
    private final StockValuationActionPolicy actionPolicy;
    private final MaterialCatalogGuard materialCatalogGuard;

    @Transactional(readOnly = true)
    public BigDecimal calculateActualCost(UUID materialId, List<UUID> warehouseIds, BigDecimal issueQuantity) {
        requirePositiveQuantity(issueQuantity);
        return calculateActualCost(materialId, warehouseIds, null, issueQuantity);
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateActualCost(UUID materialId, List<UUID> warehouseIds, UUID stockBatchId, BigDecimal issueQuantity) {
        requirePositiveQuantity(issueQuantity);
        if (stockBatchId == null) {
            return calculateWeightedAverageCost(materialId, warehouseIds, issueQuantity);
        }
        return calculateSpecificBatchCost(materialId, warehouseIds, stockBatchId, issueQuantity);
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateSpecificBatchCost(UUID materialId, List<UUID> warehouseIds, UUID stockBatchId, BigDecimal issueQuantity) {
        requirePositiveQuantity(issueQuantity);
        if (stockBatchId == null) {
            List<StockBalance> balances = stockBalanceRepository.findBalancesForValuation(TenantContext.currentTenant(), materialId, warehouseIds == null || warehouseIds.isEmpty(), warehouseIds);
            BigDecimal totalOnHand = balances.stream()
                .filter(balance -> com.enterprise.mm.security.AccessScope.canAccessCompany(balance.getCompanyId()))
                .map(sb -> nvl(sb.getQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (nvl(issueQuantity).compareTo(totalOnHand) == 0) {
                return weightedAverageSnapshot(materialId, warehouseIds).unitCost()
                    .multiply(nvl(issueQuantity))
                    .setScale(4, RoundingMode.HALF_UP);
            }
            throw new BusinessException("Specific batch costing requires stockBatchId for partial issues");
        }
        StockBatch batch = stockBatchRepository.findByIdAndIsDeletedFalse(stockBatchId)
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .filter(item -> materialId.equals(item.getMaterialId()))
            .filter(item -> warehouseIds == null || warehouseIds.isEmpty() || warehouseIds.contains(item.getWarehouseId()))
            .orElseThrow(() -> new BusinessException("Stock batch not found for specific batch valuation"));
        if (nvl(batch.getQuantity()).compareTo(nvl(issueQuantity)) < 0) {
            throw new BusinessException("Not enough stock in selected batch for specific batch valuation");
        }
        return finalUnitCost(batch)
            .multiply(nvl(issueQuantity))
            .setScale(4, RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateFifoCost(UUID materialId, List<UUID> warehouseIds, BigDecimal issueQuantity) {
        requirePositiveQuantity(issueQuantity);
        String tenantId = TenantContext.currentTenant();
        BigDecimal remaining = nvl(issueQuantity);
        BigDecimal cost = BigDecimal.ZERO;

        List<StockBalance> allBalances = stockBalanceRepository
            .findBalancesForValuation(tenantId, materialId, warehouseIds == null || warehouseIds.isEmpty(), warehouseIds)
            .stream()
            .filter(sb -> com.enterprise.mm.security.AccessScope.canAccessCompany(sb.getCompanyId()))
            .toList();

        Map<UUID, BigDecimal> availableByBatch = allBalances.stream()
            .filter(sb -> sb.getStockBatchId() != null)
            .collect(Collectors.toMap(
                StockBalance::getStockBatchId,
                sb -> nvl(sb.getQuantity()),
                BigDecimal::add
            ));

        BigDecimal noBatchAvailable = allBalances.stream()
            .filter(sb -> sb.getStockBatchId() == null)
            .map(sb -> nvl(sb.getQuantity()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<StockBatch> batches = stockBatchRepository
            .findAvailableBatchesForValuationFifo(tenantId, materialId, warehouseIds == null || warehouseIds.isEmpty(), warehouseIds)
            .stream()
            .filter(b -> com.enterprise.mm.security.AccessScope.canAccessCompany(b.getCompanyId()))
            .toList();

        BigDecimal unallocatedNegative = noBatchAvailable.signum() < 0 ? noBatchAvailable.abs() : BigDecimal.ZERO;

        for (StockBatch batch : batches) {
            BigDecimal available = availableByBatch.getOrDefault(batch.getId(), BigDecimal.ZERO);
            if (available.signum() <= 0) continue;

            if (unallocatedNegative.signum() > 0) {
                BigDecimal consume = available.min(unallocatedNegative);
                available = available.subtract(consume);
                unallocatedNegative = unallocatedNegative.subtract(consume);
            }

            if (available.signum() <= 0) continue;
            if (remaining.signum() <= 0) break;

            BigDecimal take = available.min(remaining);
            cost = cost.add(take.multiply(finalUnitCost(batch)));
            remaining = remaining.subtract(take);
        }

        if (remaining.signum() > 0 && noBatchAvailable.signum() > 0) {
            BigDecimal take = noBatchAvailable.min(remaining);
            BigDecimal waUnitCost = weightedAverageSnapshot(materialId, warehouseIds).unitCost();
            cost = cost.add(take.multiply(waUnitCost));
            remaining = remaining.subtract(take);
        }

        if (remaining.signum() > 0) {
            throw new BusinessException("Not enough stock for FIFO valuation. Missing: " + remaining.toPlainString());
        }
        return cost.setScale(4, RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateLifoCost(UUID materialId, List<UUID> warehouseIds, BigDecimal issueQuantity) {
        requirePositiveQuantity(issueQuantity);
        String tenantId = TenantContext.currentTenant();
        BigDecimal remaining = nvl(issueQuantity);
        BigDecimal cost = BigDecimal.ZERO;

        List<StockBalance> allBalances = stockBalanceRepository
            .findBalancesForValuation(tenantId, materialId, warehouseIds == null || warehouseIds.isEmpty(), warehouseIds)
            .stream()
            .filter(sb -> com.enterprise.mm.security.AccessScope.canAccessCompany(sb.getCompanyId()))
            .toList();

        Map<UUID, BigDecimal> availableByBatch = allBalances.stream()
            .filter(sb -> sb.getStockBatchId() != null)
            .collect(Collectors.toMap(
                StockBalance::getStockBatchId,
                sb -> nvl(sb.getQuantity()),
                BigDecimal::add
            ));

        BigDecimal noBatchAvailable = allBalances.stream()
            .filter(sb -> sb.getStockBatchId() == null)
            .map(sb -> nvl(sb.getQuantity()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<StockBatch> batches = stockBatchRepository
            .findAvailableBatchesForValuationLifo(tenantId, materialId, warehouseIds == null || warehouseIds.isEmpty(), warehouseIds)
            .stream()
            .filter(b -> com.enterprise.mm.security.AccessScope.canAccessCompany(b.getCompanyId()))
            .toList();

        BigDecimal unallocatedNegative = noBatchAvailable.signum() < 0 ? noBatchAvailable.abs() : BigDecimal.ZERO;

        for (StockBatch batch : batches) {
            BigDecimal available = availableByBatch.getOrDefault(batch.getId(), BigDecimal.ZERO);
            if (available.signum() <= 0) continue;

            if (unallocatedNegative.signum() > 0) {
                BigDecimal consume = available.min(unallocatedNegative);
                available = available.subtract(consume);
                unallocatedNegative = unallocatedNegative.subtract(consume);
            }

            if (available.signum() <= 0) continue;
            if (remaining.signum() <= 0) break;

            BigDecimal take = available.min(remaining);
            cost = cost.add(take.multiply(finalUnitCost(batch)));
            remaining = remaining.subtract(take);
        }

        if (remaining.signum() > 0 && noBatchAvailable.signum() > 0) {
            BigDecimal take = noBatchAvailable.min(remaining);
            BigDecimal waUnitCost = weightedAverageSnapshot(materialId, warehouseIds).unitCost();
            cost = cost.add(take.multiply(waUnitCost));
            remaining = remaining.subtract(take);
        }

        if (remaining.signum() > 0) {
            throw new BusinessException("Not enough stock for LIFO valuation. Missing: " + remaining.toPlainString());
        }
        return cost.setScale(4, RoundingMode.HALF_UP);
    }
    @Transactional(readOnly = true)
    public BigDecimal calculateWeightedAverageCost(UUID materialId, List<UUID> warehouseIds, BigDecimal issueQuantity) {
        requirePositiveQuantity(issueQuantity);
        ValuationSnapshot snapshot = weightedAverageSnapshot(materialId, warehouseIds);
        if (snapshot.quantity().signum() <= 0) {
            throw new BusinessException("No stock for weighted average valuation");
        }
        return snapshot.unitCost()
            .multiply(nvl(issueQuantity))
            .setScale(4, RoundingMode.HALF_UP);
    }
    @Transactional(readOnly = true)
    public BigDecimal calculateMovingAverageCost(UUID materialId, List<UUID> warehouseIds, BigDecimal issueQuantity) {
        requirePositiveQuantity(issueQuantity);
        return movingAverageSnapshot(materialId, warehouseIds).unitCost()
            .multiply(nvl(issueQuantity))
            .setScale(4, RoundingMode.HALF_UP);
    }
    @Transactional(readOnly = true)
    public BigDecimal calculateMarketPriceCost(UUID materialId, List<UUID> warehouseIds, BigDecimal issueQuantity) {
        requirePositiveQuantity(issueQuantity);
        return marketBasedCost(materialId, warehouseIds, issueQuantity, false);
    }
    @Transactional(readOnly = true)
    public BigDecimal calculateLowerOfCostOrMarketCost(UUID materialId, List<UUID> warehouseIds, BigDecimal issueQuantity) {
        requirePositiveQuantity(issueQuantity);
        BigDecimal market = calculateMarketPriceCost(materialId, warehouseIds, issueQuantity);
        BigDecimal cost = calculateWeightedAverageCost(materialId, warehouseIds, issueQuantity);
        return cost.min(market).setScale(4, RoundingMode.HALF_UP);
    }
    @Transactional(readOnly = true)
    public BigDecimal calculateNetRealizableValueCost(UUID materialId, List<UUID> warehouseIds, BigDecimal issueQuantity) {
        requirePositiveQuantity(issueQuantity);
        return marketBasedCost(materialId, warehouseIds, issueQuantity, true);
    }
    @Transactional
    public StockValuation recalculate(UUID materialId, UUID warehouseId, ValuationMethod method) {
        actionPolicy.requireActionAllowed("RECALCULATE");
        materialCatalogGuard.requireActiveTmbMaterial(materialId, "materialId");
        ValuationSnapshot snapshot = snapshot(materialId, warehouseId, method);
        StockValuation valuation = stockValuationRepository.findByTenantIdAndMaterialIdAndWarehouseIdAndValuationMethodAndIsDeletedFalse(TenantContext.currentTenant(), materialId, warehouseId, method)
            .orElseGet(StockValuation::new);
        valuation.setCode("VAL-" + materialId.toString().substring(0, 8) + "-" + method.name());
        valuation.setName("Valuation " + method.name());
        valuation.setStatus("CALCULATED");
        valuation.setMaterialId(materialId);
        valuation.setWarehouseId(warehouseId);
        valuation.setValuationMethod(method);
        valuation.setQuantity(snapshot.quantity());
        valuation.setUnitCost(snapshot.unitCost());
        valuation.setTotalValue(snapshot.totalValue());
        valuation.setValuationBasis(snapshot.basis());
        valuation.setCarryingValue(snapshot.totalValue().subtract(nvl(valuation.getImpairmentReserve())).max(BigDecimal.ZERO));
        valuation.setCalculatedAt(LocalDateTime.now());
        StockValuation saved = stockValuationRepository.save(valuation);
        auditService.log("StockValuation", saved.getId(), "RECALCULATE", method.name(), null, null);
        return saved;
    }
    @Transactional
    public StockValuation applyRevaluation(UUID materialId, UUID warehouseId, ValuationMethod method, BigDecimal newUnitCost, String reason) {
        actionPolicy.requireActionAllowed("REVALUE");
        StockValuation valuation = recalculate(materialId, warehouseId, method);
        BigDecimal oldTotal = nvl(valuation.getTotalValue());
        BigDecimal newTotal = nvl(valuation.getQuantity()).multiply(nvl(newUnitCost)).setScale(4, RoundingMode.HALF_UP);
        valuation.setUnitCost(nvl(newUnitCost));
        valuation.setTotalValue(newTotal);
        valuation.setRevaluationAmount(newTotal.subtract(oldTotal));
        valuation.setCarryingValue(newTotal.subtract(nvl(valuation.getImpairmentReserve())).max(BigDecimal.ZERO));
        valuation.setValuationBasis("REVALUATION" + (reason == null || reason.isBlank() ? "" : ": " + reason));
        StockValuation saved = stockValuationRepository.save(valuation);
        auditService.log("StockValuation", saved.getId(), "REVALUATION", saved.getRevaluationAmount().toPlainString(), null, null);
        return saved;
    }
    @Transactional
    public StockValuation applyImpairmentReserve(UUID materialId, UUID warehouseId, ValuationMethod method, BigDecimal reserveAmount, String reason) {
        actionPolicy.requireActionAllowed("IMPAIR");
        StockValuation valuation = recalculate(materialId, warehouseId, method);
        valuation.setImpairmentReserve(nvl(reserveAmount).max(BigDecimal.ZERO));
        valuation.setCarryingValue(nvl(valuation.getTotalValue()).subtract(valuation.getImpairmentReserve()).max(BigDecimal.ZERO));
        valuation.setValuationBasis("IMPAIRMENT_RESERVE" + (reason == null || reason.isBlank() ? "" : ": " + reason));
        StockValuation saved = stockValuationRepository.save(valuation);
        auditService.log("StockValuation", saved.getId(), "IMPAIRMENT_RESERVE", saved.getImpairmentReserve().toPlainString(), null, null);
        return saved;
    }

    @Transactional
    public void reduceStockBalanceValueForImpairment(UUID materialId, UUID warehouseId, BigDecimal reserveAmount) {
        Material material = materialCatalogGuard.requireActiveTmbMaterial(materialId, "materialId");
        if (openingImportGuard != null) openingImportGuard.assertOperationsAllowed(material.getCompanyId());
        BigDecimal remaining = nvl(reserveAmount).setScale(4, RoundingMode.HALF_UP);
        if (remaining.signum() <= 0) {
            return;
        }
        List<StockBalance> balances = currentCompanyBalances(materialId, java.util.List.of(warehouseId)).stream()
            .filter(balance -> nvl(balance.getTotalValue()).signum() > 0)
            .toList();
        BigDecimal totalValue = balances.stream()
            .map(StockBalance::getTotalValue)
            .map(this::nvl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalValue.signum() <= 0) {
            return;
        }
        BigDecimal amountToApply = remaining.min(totalValue);
        BigDecimal remainder = amountToApply;
        for (int i = 0; i < balances.size(); i++) {
            StockBalance balance = balances.get(i);
            BigDecimal amount = i == balances.size() - 1
                ? remainder
                : amountToApply.multiply(nvl(balance.getTotalValue())).divide(totalValue, 4, RoundingMode.HALF_UP);
            balance.setTotalValue(nvl(balance.getTotalValue()).subtract(amount).max(BigDecimal.ZERO));
            stockBalanceRepository.save(balance);
            remainder = remainder.subtract(amount);
        }
    }
    private ValuationSnapshot snapshot(UUID materialId, UUID warehouseId, ValuationMethod method) {
        return switch (method) {
            case ACTUAL_COST -> actualCostSnapshot(materialId, java.util.List.of(warehouseId));
            case SPECIFIC_BATCH -> actualCostSnapshot(materialId, java.util.List.of(warehouseId));
            case FIFO -> fifoSnapshot(materialId, java.util.List.of(warehouseId));
            case LIFO -> lifoSnapshot(materialId, java.util.List.of(warehouseId));
            case WEIGHTED_AVERAGE -> weightedAverageSnapshot(materialId, java.util.List.of(warehouseId));
            case MOVING_AVERAGE -> movingAverageSnapshot(materialId, java.util.List.of(warehouseId));
            case MARKET_PRICE -> marketPriceSnapshot(materialId, java.util.List.of(warehouseId));
            case LOWER_OF_COST_OR_MARKET -> lowerOfCostOrMarketSnapshot(materialId, java.util.List.of(warehouseId));
            case NET_REALIZABLE_VALUE -> netRealizableValueSnapshot(materialId, java.util.List.of(warehouseId));
        };
    }
    private ValuationSnapshot actualCostSnapshot(UUID materialId, List<UUID> warehouseIds) {
        return weightedAverageSnapshot(materialId, warehouseIds).withBasis("ACTUAL_COST_BOOK_VALUE");
    }
    private ValuationSnapshot fifoSnapshot(UUID materialId, List<UUID> warehouseIds) {
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal totalValue = BigDecimal.ZERO;
        List<StockBatch> batches = stockBatchRepository.findBatchesForValuationAsc(
                TenantContext.currentTenant(), materialId, warehouseIds == null || warehouseIds.isEmpty(), warehouseIds, BigDecimal.ZERO
            ).stream()
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .toList();
        for (StockBatch batch : batches) {
            quantity = quantity.add(nvl(batch.getQuantity()));
            totalValue = totalValue.add(nvl(batch.getQuantity()).multiply(finalUnitCost(batch)));
        }
        if (quantity.signum() <= 0) {
            return weightedAverageSnapshot(materialId, warehouseIds).withBasis("FIFO_FALLBACK_BALANCE");
        }
        return ValuationSnapshot.of(quantity, totalValue, "FIFO_BATCHES");
    }
    private ValuationSnapshot lifoSnapshot(UUID materialId, List<UUID> warehouseIds) {
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal totalValue = BigDecimal.ZERO;
        List<StockBatch> batches = stockBatchRepository.findBatchesForValuationDesc(
                TenantContext.currentTenant(), materialId, warehouseIds == null || warehouseIds.isEmpty(), warehouseIds, BigDecimal.ZERO
            ).stream()
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .toList();
        for (StockBatch batch : batches) {
            quantity = quantity.add(nvl(batch.getQuantity()));
            totalValue = totalValue.add(nvl(batch.getQuantity()).multiply(finalUnitCost(batch)));
        }
        if (quantity.signum() <= 0) {
            return weightedAverageSnapshot(materialId, warehouseIds).withBasis("LIFO_FALLBACK_BALANCE");
        }
        return ValuationSnapshot.of(quantity, totalValue, "LIFO_BATCHES");
    }
    private ValuationSnapshot weightedAverageSnapshot(UUID materialId, List<UUID> warehouseIds) {
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal totalValue = BigDecimal.ZERO;
        for (StockBalance balance : currentCompanyBalances(materialId, warehouseIds)) {
            quantity = quantity.add(nvl(balance.getQuantity()));
            totalValue = totalValue.add(nvl(balance.getTotalValue()));
        }
        return ValuationSnapshot.of(quantity, totalValue, "WEIGHTED_BALANCES");
    }
    private ValuationSnapshot movingAverageSnapshot(UUID materialId, List<UUID> warehouseIds) {
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal totalValue = BigDecimal.ZERO;
        for (StockMovement movement : stockMovementRepository.findMovementsForValuationAsc(
            TenantContext.currentTenant(), materialId, warehouseIds == null || warehouseIds.isEmpty(), warehouseIds, StockMovementStatus.effectiveStatusNames()
        )) {
            if (!com.enterprise.mm.security.AccessScope.canAccessCompany(movement.getCompanyId())) {
                continue;
            }
            BigDecimal movementQuantity = nvl(movement.getQuantity());
            BigDecimal movingUnitCost = quantity.signum() == 0 ? nvl(movement.getUnitCost()) : totalValue.divide(quantity, 8, RoundingMode.HALF_UP);
            MovementType type = movement.getMovementType();
            if (type == MovementType.RECEIPT || type == MovementType.RETURN || type == MovementType.ADJUSTMENT) {
                quantity = quantity.add(movementQuantity);
                totalValue = totalValue.add(movementQuantity.multiply(nvl(movement.getUnitCost())));
            } else if (type == MovementType.ISSUE || type == MovementType.WRITE_OFF || type == MovementType.REVERSAL) {
                quantity = quantity.subtract(movementQuantity).max(BigDecimal.ZERO);
                totalValue = totalValue.subtract(movementQuantity.multiply(movingUnitCost)).max(BigDecimal.ZERO);
            }
        }
        if (quantity.signum() <= 0) {
            return weightedAverageSnapshot(materialId, warehouseIds).withBasis("MOVING_AVERAGE_FALLBACK_BALANCE");
        }
        return ValuationSnapshot.of(quantity, totalValue, "MOVING_AVERAGE_EFFECTIVE_MOVEMENTS");
    }
    private ValuationSnapshot marketPriceSnapshot(UUID materialId, List<UUID> warehouseIds) {
        UUID warehouseId = warehouseIds != null && !warehouseIds.isEmpty() ? warehouseIds.get(0) : null;
        BigDecimal quantity = currentQuantity(materialId, warehouseIds);
        BigDecimal unitCost = materialMarketPriceService.approvedMarketUnitPrice(materialId, warehouseId, java.time.LocalDate.now());
        BigDecimal totalValue = quantity.multiply(unitCost).setScale(4, RoundingMode.HALF_UP);
        return new ValuationSnapshot(quantity.setScale(StockUomPolicy.MAX_QUANTITY_SCALE, RoundingMode.UNNECESSARY), unitCost.setScale(4, RoundingMode.HALF_UP), totalValue, "APPROVED_MARKET_PRICE");
    }
    private ValuationSnapshot lowerOfCostOrMarketSnapshot(UUID materialId, List<UUID> warehouseIds) {
        ValuationSnapshot cost = weightedAverageSnapshot(materialId, warehouseIds);
        ValuationSnapshot market = marketPriceSnapshot(materialId, warehouseIds);
        if (market.totalValue().compareTo(cost.totalValue()) < 0) {
            return new ValuationSnapshot(market.quantity(), market.unitCost(), market.totalValue(), "LOWER_OF_COST_OR_MARKET_MARKET");
        }
        return new ValuationSnapshot(cost.quantity(), cost.unitCost(), cost.totalValue(), "LOWER_OF_COST_OR_MARKET_COST");
    }
    private ValuationSnapshot netRealizableValueSnapshot(UUID materialId, List<UUID> warehouseIds) {
        UUID warehouseId = warehouseIds != null && !warehouseIds.isEmpty() ? warehouseIds.get(0) : null;
        BigDecimal quantity = currentQuantity(materialId, warehouseIds);
        BigDecimal unitCost = materialMarketPriceService.approvedNetRealizableUnitPrice(materialId, warehouseId, java.time.LocalDate.now());
        BigDecimal totalValue = quantity.multiply(unitCost).setScale(4, RoundingMode.HALF_UP);
        return new ValuationSnapshot(quantity.setScale(StockUomPolicy.MAX_QUANTITY_SCALE, RoundingMode.UNNECESSARY), unitCost.setScale(4, RoundingMode.HALF_UP), totalValue, "APPROVED_NET_REALIZABLE_VALUE");
    }
    private BigDecimal currentQuantity(UUID materialId, List<UUID> warehouseIds) {
        return currentCompanyBalances(materialId, warehouseIds).stream()
            .map(StockBalance::getQuantity)
            .map(this::nvl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    private Material material(UUID materialId) {
        return materialRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), materialId)
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Material not found for valuation"));
    }
    private void requirePositiveQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new BusinessException("Valuation quantity must be positive");
        }
    }

    private BigDecimal marketBasedCost(UUID materialId, List<UUID> warehouseIds, BigDecimal quantity, boolean netRealizable) {
        // A single-warehouse quote retains its existing unit-price semantics.
        if (warehouseIds != null && warehouseIds.size() == 1) {
            return quantity.multiply(approvedPrice(materialId, warehouseIds.getFirst(), netRealizable))
                .setScale(4, RoundingMode.HALF_UP);
        }
        Map<UUID, BigDecimal> quantities = currentCompanyBalances(materialId, warehouseIds).stream()
            .collect(Collectors.toMap(StockBalance::getWarehouseId, balance -> nvl(balance.getQuantity()), BigDecimal::add));
        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal totalValue = BigDecimal.ZERO;
        for (var entry : quantities.entrySet()) {
            if (entry.getValue().signum() <= 0) continue;
            totalQuantity = totalQuantity.add(entry.getValue());
            totalValue = totalValue.add(entry.getValue().multiply(approvedPrice(materialId, entry.getKey(), netRealizable)));
        }
        if (totalQuantity.signum() <= 0) {
            throw new BusinessException("No stock for multi-warehouse market valuation");
        }
        // For a partial quote, distribute quantity proportionally to each warehouse's on-hand stock.
        return totalValue.multiply(quantity).divide(totalQuantity, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal approvedPrice(UUID materialId, UUID warehouseId, boolean netRealizable) {
        return netRealizable
            ? materialMarketPriceService.approvedNetRealizableUnitPrice(materialId, warehouseId, java.time.LocalDate.now())
            : materialMarketPriceService.approvedMarketUnitPrice(materialId, warehouseId, java.time.LocalDate.now());
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal finalUnitCost(StockBatch batch) {
        return nvl(batch.getFinalUnitCost()).signum() > 0 ? nvl(batch.getFinalUnitCost()) : nvl(batch.getUnitCost());
    }
    private List<StockBalance> currentCompanyBalances(UUID materialId, List<UUID> warehouseIds) {
        return stockBalanceRepository.findBalancesForValuation(TenantContext.currentTenant(), materialId, warehouseIds == null || warehouseIds.isEmpty(), warehouseIds).stream()
            .filter(balance -> com.enterprise.mm.security.AccessScope.canAccessCompany(balance.getCompanyId()))
            .toList();
    }
    private record ValuationSnapshot(BigDecimal quantity, BigDecimal unitCost, BigDecimal totalValue, String basis) {
        private static ValuationSnapshot of(BigDecimal quantity, BigDecimal totalValue, String basis) {
            BigDecimal normalizedQuantity = quantity.setScale(StockUomPolicy.MAX_QUANTITY_SCALE, RoundingMode.UNNECESSARY);
            BigDecimal normalizedTotal = totalValue.setScale(4, RoundingMode.HALF_UP);
            BigDecimal unitCost = normalizedQuantity.signum() == 0
                ? BigDecimal.ZERO
                : normalizedTotal.divide(normalizedQuantity, 4, RoundingMode.HALF_UP);
            return new ValuationSnapshot(normalizedQuantity, unitCost, normalizedTotal, basis);
        }
        private ValuationSnapshot withBasis(String nextBasis) {
            return new ValuationSnapshot(quantity, unitCost, totalValue, nextBasis);
        }
    }
}
