package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.InventoryCostLayer;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockBatch;
import com.enterprise.mm.entity.StockCostMovement;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.payload.InventoryValuationReconciliationIssueResponse;
import com.enterprise.mm.repository.InventoryCostLayerRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockBatchRepository;
import com.enterprise.mm.repository.StockCostMovementRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryValuationReconciliationService {
    private static final BigDecimal VALUE_TOLERANCE = new BigDecimal("0.01");

    private final StockBalanceRepository stockBalanceRepository;
    private final InventoryCostLayerRepository costLayerRepository;
    private final StockCostMovementRepository costMovementRepository;
    private final StockMovementRepository stockMovementRepository;
    private final StockBatchRepository stockBatchRepository;

    @Transactional(readOnly = true)
    public List<InventoryValuationReconciliationIssueResponse> run(UUID warehouseId) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        List<StockBalance> balances = stockBalanceRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(tenantId, companyId).stream()
            .filter(balance -> warehouseId == null || warehouseId.equals(balance.getWarehouseId()))
            .toList();
        List<InventoryCostLayer> layers = costLayerRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(tenantId, companyId).stream()
            .filter(layer -> warehouseId == null || warehouseId.equals(layer.getWarehouseId()))
            .toList();
        List<StockCostMovement> costMovements = costMovementRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(tenantId, companyId).stream()
            .filter(entry -> warehouseId == null || warehouseId.equals(entry.getWarehouseId()))
            .toList();
        List<StockMovement> stockMovements = stockMovementRepository.findByTenantIdAndCompanyIdAndIsDeletedFalseOrderByCreatedAtDesc(tenantId, companyId).stream()
            .filter(movement -> warehouseId == null || warehouseId.equals(movement.getWarehouseId()) || warehouseId.equals(movement.getTargetWarehouseId()))
            .toList();
        List<StockBatch> batches = stockBatchRepository.findByTenantIdAndCompanyIdAndQuantityGreaterThanAndIsDeletedFalse(tenantId, companyId, BigDecimal.ZERO).stream()
            .filter(batch -> warehouseId == null || warehouseId.equals(batch.getWarehouseId()))
            .toList();

        Map<BatchKey, Totals> physicalByBatch = new HashMap<>();
        for (StockBalance balance : balances) {
            physicalByBatch.computeIfAbsent(batchKey(balance), ignored -> new Totals())
                .add(balance.getQuantity(), balance.getTotalValue());
        }
        Map<BatchKey, LayerEvidence> evidenceByBatch = new HashMap<>();
        for (InventoryCostLayer layer : layers) {
            if ("ACTIVE".equals(layer.getStatus()) && nvl(layer.getRemainingQuantity()).signum() > 0)
                evidenceByBatch.computeIfAbsent(batchKey(layer), ignored -> new LayerEvidence()).add(layer);
        }
        Map<GroupKey, Totals> balanceTotals = new HashMap<>();
        Map<GroupKey, Totals> layerTotals = new HashMap<>();
        List<InventoryValuationReconciliationIssueResponse> issues = new ArrayList<>();

        for (StockBalance balance : balances) {
            GroupKey key = new GroupKey(balance.getCompanyId(), balance.getMaterialId(), balance.getWarehouseId());
            balanceTotals.computeIfAbsent(key, ignored -> new Totals()).add(nvl(balance.getQuantity()), nvl(balance.getTotalValue()));
            if (nvl(balance.getQuantity()).signum() < 0) {
                issues.add(issue("NEGATIVE_STOCK", "BLOCKER", balance.getCompanyId(), balance.getMaterialId(), balance.getWarehouseId(), balance.getStockBatchId(), null, null, null, nvl(balance.getQuantity()), null, nvl(balance.getTotalValue()), null, "Negative stock balance"));
            }
            if (nvl(balance.getQuantity()).signum() > 0 && nvl(balance.getTotalValue()).signum() <= 0
                && !(nvl(balance.getTotalValue()).signum() == 0
                    && approvedZeroValue(physicalByBatch.get(batchKey(balance)), evidenceByBatch.get(batchKey(balance))))) {
                issues.add(issue("ZERO_COST_STOCK", "BLOCKER", balance.getCompanyId(), balance.getMaterialId(), balance.getWarehouseId(), balance.getStockBatchId(), null, null, null, nvl(balance.getQuantity()), null, nvl(balance.getTotalValue()), null, "Positive stock has zero or negative value"));
            }
        }

        for (InventoryCostLayer layer : layers) {
            if (!"ACTIVE".equals(layer.getStatus()) || nvl(layer.getRemainingQuantity()).signum() <= 0) {
                continue;
            }
            GroupKey key = new GroupKey(layer.getCompanyId(), layer.getMaterialId(), layer.getWarehouseId());
            layerTotals.computeIfAbsent(key, ignored -> new Totals()).add(nvl(layer.getRemainingQuantity()), nvl(layer.getTotalCost()));
        }

        Set<GroupKey> keys = new HashSet<>();
        keys.addAll(balanceTotals.keySet());
        keys.addAll(layerTotals.keySet());
        for (GroupKey key : keys) {
            Totals balance = balanceTotals.getOrDefault(key, new Totals());
            Totals layer = layerTotals.getOrDefault(key, new Totals());
            if (balance.quantity.signum() > 0 && layer.quantity.signum() <= 0) {
                issues.add(issue("MISSING_COST_LAYER", "BLOCKER", key.companyId(), key.materialId(), key.warehouseId(), null, null, null, null, balance.quantity, layer.quantity, balance.value, layer.value, "Positive stock balance has no active cost layer"));
            }
            if (balance.quantity.signum() <= 0 && layer.quantity.signum() > 0) {
                issues.add(issue("ORPHAN_COST_LAYER", "BLOCKER", key.companyId(), key.materialId(), key.warehouseId(), null, null, null, null, balance.quantity, layer.quantity, balance.value, layer.value, "Active cost layer exists without positive stock balance"));
            }
            if (balance.quantity.compareTo(layer.quantity) != 0) {
                issues.add(issue("QUANTITY_MISMATCH", "BLOCKER", key.companyId(), key.materialId(), key.warehouseId(), null, null, null, null, balance.quantity, layer.quantity, balance.value, layer.value, "Stock balance quantity does not match active cost layers"));
            }
            if (balance.value.subtract(layer.value).abs().compareTo(VALUE_TOLERANCE) > 0) {
                issues.add(issue("VALUE_MISMATCH", "BLOCKER", key.companyId(), key.materialId(), key.warehouseId(), null, null, null, null, balance.quantity, layer.quantity, balance.value, layer.value, "Stock balance value does not match active cost layers"));
            }
        }

        Map<UUID, StockMovement> movementById = new HashMap<>();
        for (StockMovement movement : stockMovements) {
            movementById.put(movement.getId(), movement);
        }
        Map<UUID, InventoryCostLayer> layerById = new HashMap<>();
        for (InventoryCostLayer layer : layers) {
            layerById.put(layer.getId(), layer);
        }
        Set<UUID> movementsWithLedger = new HashSet<>();
        for (StockCostMovement entry : costMovements) {
            if (entry.getStockMovementId() == null && (blank(entry.getSourceDocumentType()) || entry.getSourceDocumentId() == null)) {
                issues.add(issue("COST_MOVEMENT_WITHOUT_SOURCE", "BLOCKER", entry.getCompanyId(), entry.getMaterialId(), entry.getWarehouseId(), null, null, entry.getCostLayerId(), entry.getId(), null, null, null, entry.getTotalCost(), "Cost movement has neither stock movement nor source document"));
            }
            if (entry.getStockMovementId() != null) {
                movementsWithLedger.add(entry.getStockMovementId());
                if (!movementById.containsKey(entry.getStockMovementId())) {
                    issues.add(issue("ORPHAN_COST_MOVEMENT", "BLOCKER", entry.getCompanyId(), entry.getMaterialId(), entry.getWarehouseId(), null, entry.getStockMovementId(), entry.getCostLayerId(), entry.getId(), null, null, null, entry.getTotalCost(), "Cost movement references a missing stock movement"));
                }
            }
            if (entry.getCostLayerId() != null && !layerById.containsKey(entry.getCostLayerId())) {
                issues.add(issue("ORPHAN_COST_MOVEMENT", "BLOCKER", entry.getCompanyId(), entry.getMaterialId(), entry.getWarehouseId(), null, entry.getStockMovementId(), entry.getCostLayerId(), entry.getId(), null, null, null, entry.getTotalCost(), "Cost movement references a missing cost layer"));
            }
        }
        for (StockMovement movement : stockMovements) {
            if ("POSTED".equals(movement.getStatus()) && !movementsWithLedger.contains(movement.getId())) {
                issues.add(issue("MOVEMENT_WITHOUT_LEDGER", "BLOCKER", movement.getCompanyId(), movement.getMaterialId(), movement.getWarehouseId(), movement.getStockBatchId(), movement.getId(), null, null, nvl(movement.getQuantity()), null, nvl(movement.getTotalCost()), null, "Posted stock movement has no cost ledger entry"));
            }
        }

        for (StockBatch batch : batches) {
            var key = new BatchKey(batch.getCompanyId(), batch.getMaterialId(), batch.getWarehouseId(), batch.getId());
            var proof = evidenceByBatch.get(key);
            Totals layer = proof == null ? new Totals() : proof.totals;
            Totals physical = physicalByBatch.getOrDefault(key, new Totals());
            boolean authoritative = proof != null && proof.authoritative;
            boolean approvedBookValue = batch.getFinalUnitCost() != null && batch.getFinalUnitCost().signum() == 0
                && authoritative && physical.value.signum() >= 0 && layer.value.signum() >= 0
                && nvl(batch.getQuantity()).compareTo(physical.quantity) == 0
                && physical.quantity.compareTo(layer.quantity) == 0
                && physical.value.subtract(layer.value).abs().compareTo(VALUE_TOLERANCE) <= 0;
            if (nvl(batch.getFinalUnitCost()).signum() <= 0 && !approvedBookValue) {
                issues.add(issue("BATCH_LAYER_MISMATCH", "BLOCKER", batch.getCompanyId(), batch.getMaterialId(), batch.getWarehouseId(), batch.getId(), null, null, null, nvl(batch.getQuantity()), null, nvl(batch.getFinalUnitCost()), null, "Positive stock batch has no final unit cost"));
            }
            // An authoritative layer stores exact book value; its six-place unit cost is a display approximation.
            BigDecimal batchValue = authoritative && nvl(batch.getQuantity()).compareTo(physical.quantity) == 0
                ? physical.value : nvl(batch.getQuantity()).multiply(nvl(batch.getFinalUnitCost())).setScale(4, RoundingMode.HALF_UP);
            if (nvl(batch.getQuantity()).compareTo(layer.quantity) != 0
                || authoritative && nvl(batch.getQuantity()).compareTo(physical.quantity) != 0) {
                issues.add(issue("BATCH_LAYER_QUANTITY_MISMATCH", "BLOCKER", batch.getCompanyId(), batch.getMaterialId(), batch.getWarehouseId(), batch.getId(), null, null, null, nvl(batch.getQuantity()), layer.quantity, batchValue, layer.value, "Stock batch quantity does not match active cost layers or physical balances"));
            }
            if (batchValue.subtract(layer.value).abs().compareTo(VALUE_TOLERANCE) > 0) {
                issues.add(issue("BATCH_LAYER_VALUE_MISMATCH", "BLOCKER", batch.getCompanyId(), batch.getMaterialId(), batch.getWarehouseId(), batch.getId(), null, null, null, nvl(batch.getQuantity()), layer.quantity, batchValue, layer.value, "Stock batch final value does not match active cost layer value"));
            }
        }

        return issues;
    }

    @Transactional(readOnly = true)
    public List<InventoryValuationReconciliationIssueResponse> blockerIssues(UUID warehouseId) {
        return run(warehouseId).stream()
            .filter(issue -> "BLOCKER".equals(issue.severity()))
            .toList();
    }

    private InventoryValuationReconciliationIssueResponse issue(
        String type,
        String severity,
        UUID companyId,
        UUID materialId,
        UUID warehouseId,
        UUID stockBatchId,
        UUID stockMovementId,
        UUID costLayerId,
        UUID costMovementId,
        BigDecimal balanceQuantity,
        BigDecimal layerQuantity,
        BigDecimal balanceValue,
        BigDecimal layerValue,
        String message
    ) {
        String seed = String.join("|",
            type,
            severity,
            Objects.toString(companyId, ""),
            Objects.toString(materialId, ""),
            Objects.toString(warehouseId, ""),
            Objects.toString(stockBatchId, ""),
            Objects.toString(stockMovementId, ""),
            Objects.toString(costLayerId, ""),
            Objects.toString(costMovementId, ""),
            message
        );
        return new InventoryValuationReconciliationIssueResponse(
            UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)),
            type,
            severity,
            companyId,
            materialId,
            warehouseId,
            stockBatchId,
            stockMovementId,
            costLayerId,
            costMovementId,
            quantityScale(balanceQuantity),
            quantityScale(layerQuantity),
            scale(balanceValue),
            scale(layerValue),
            message
        );
    }

    private BigDecimal quantityScale(BigDecimal value) {
        return value == null ? null : value.setScale(StockUomPolicy.MAX_QUANTITY_SCALE, RoundingMode.UNNECESSARY);
    }

    private boolean approvedZeroValue(Totals physical, LayerEvidence proof) {
        return physical != null && physical.quantity.signum() > 0 && physical.value.signum() == 0
            && proof != null && proof.authoritative && proof.totals.value.signum() == 0
            && physical.quantity.compareTo(proof.totals.quantity) == 0;
    }

    private BatchKey batchKey(StockBalance balance) {
        return new BatchKey(balance.getCompanyId(), balance.getMaterialId(), balance.getWarehouseId(), balance.getStockBatchId());
    }

    private BatchKey batchKey(InventoryCostLayer layer) {
        return new BatchKey(layer.getCompanyId(), layer.getMaterialId(), layer.getWarehouseId(), layer.getStockBatchId());
    }

    private record BatchKey(UUID companyId, UUID materialId, UUID warehouseId, UUID stockBatchId) {}

    private static final class LayerEvidence {
        private final Totals totals = new Totals();
        private boolean authoritative = true;
        private void add(InventoryCostLayer layer) {
            totals.add(layer.getRemainingQuantity(), layer.getTotalCost());
            authoritative &= layer.isAuthoritativeValue() && layer.getTotalCost() != null && layer.getTotalCost().signum() >= 0;
        }
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record GroupKey(UUID companyId, UUID materialId, UUID warehouseId) {
    }

    private static final class Totals {
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal value = BigDecimal.ZERO;

        private void add(BigDecimal nextQuantity, BigDecimal nextValue) {
            quantity = quantity.add(nextQuantity == null ? BigDecimal.ZERO : nextQuantity);
            value = value.add(nextValue == null ? BigDecimal.ZERO : nextValue);
        }
    }
}
