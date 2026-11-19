package com.enterprise.mm.service;

import com.enterprise.mm.entity.NonMovingStockCaseLine;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.enums.NonMovingStockExecutionOutputStatus;
import com.enterprise.mm.enums.NonMovingStockReconciliationStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.NonMovingStockExecutionOutputRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NonMovingStockReconciliationService {
    private final StockBalanceRepository balanceRepository;
    private final InventoryPeriodGuardService periodGuardService;
    private final NonMovingStockExecutionOutputRepository outputRepository;



    public ReconciliationResult reconcile(
        NonMovingStockCaseLine line,
        BigDecimal approvedQuantity,
        UUID decisionLineId
    ) {
        if (line == null || approvedQuantity == null || approvedQuantity.signum() <= 0) {
            throw new BusinessException("Positive approved quantity and case line are required for reconciliation");
        }
        if (decisionLineId != null && outputRepository
            .existsByTenantIdAndDecisionLineIdAndOutputStatusInAndIsDeletedFalse(
                line.getTenantId(),
                decisionLineId,
                List.of(
                    NonMovingStockExecutionOutputStatus.POSTED.name(),
                    NonMovingStockExecutionOutputStatus.REVERSED.name()
                )
            )) {
            return result(NonMovingStockReconciliationStatus.ALREADY_EXECUTED, BigDecimal.ZERO, "Decision line already has an effective output");
        }
        try {
            periodGuardService.ensureOpen(line.getWarehouseId(), LocalDate.now(), "non-moving stock execution");
        } catch (BusinessException exception) {
            return result(NonMovingStockReconciliationStatus.PERIOD_CLOSED, BigDecimal.ZERO, exception.getMessage());
        }

        return balanceRepository.findCompanyBalance(
                line.getTenantId(),
                line.getCompanyId(),
                line.getMaterialId(),
                line.getWarehouseId(),
                line.getStorageLocationId() == null,
                line.getStorageLocationId(),
                true,
                null,
                line.getBatchId() == null,
                line.getBatchId()
            )
            .map(balance -> reconcileExact(line, balance, approvedQuantity))
            .orElseGet(() -> reconcileMoved(line));
    }

    private ReconciliationResult reconcileExact(
        NonMovingStockCaseLine line,
        StockBalance balance,
        BigDecimal approvedQuantity
    ) {
        BigDecimal available = nvl(balance.getAvailableQuantity());
        if (nvl(balance.getQuarantineQuantity()).signum() > 0 && available.signum() <= 0) {
            return result(NonMovingStockReconciliationStatus.STOCK_BLOCKED, available, "Live stock is quarantined or blocked");
        }
        if (available.compareTo(approvedQuantity) < 0) {
            return result(NonMovingStockReconciliationStatus.INSUFFICIENT_STOCK, available, "Live available quantity is below approved quantity");
        }
        BigDecimal snapshotAvailable = nvl(line.getAvailableQuantity()).signum() > 0
            ? nvl(line.getAvailableQuantity())
            : nvl(line.getQuantity());
        if (available.compareTo(snapshotAvailable) < 0) {
            return result(NonMovingStockReconciliationStatus.QUANTITY_REDUCED, available, "Live quantity decreased after case creation");
        }
        return result(NonMovingStockReconciliationStatus.READY, available, "Live balance matches the approved snapshot");
    }

    private ReconciliationResult reconcileMoved(NonMovingStockCaseLine line) {
        List<StockBalance> candidates = balanceRepository
            .findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
                line.getTenantId(),
                line.getMaterialId(),
                line.getWarehouseId()
            ).stream()
            .filter(balance -> Objects.equals(line.getCompanyId(), balance.getCompanyId()))
            .filter(balance -> nvl(balance.getQuantity()).signum() > 0)
            .toList();
        if (candidates.stream().anyMatch(balance ->
            Objects.equals(line.getStorageLocationId(), balance.getStorageLocationId())
                && !Objects.equals(line.getBatchId(), balance.getStockBatchId()))) {
            return result(NonMovingStockReconciliationStatus.BATCH_CHANGED, totalAvailable(candidates), "Stock batch changed after case creation");
        }
        if (!candidates.isEmpty()) {
            return result(NonMovingStockReconciliationStatus.LOCATION_CHANGED, totalAvailable(candidates), "Stock location changed after case creation");
        }
        return result(NonMovingStockReconciliationStatus.INSUFFICIENT_STOCK, BigDecimal.ZERO, "No matching live stock remains");
    }

    private BigDecimal totalAvailable(List<StockBalance> balances) {
        return balances.stream().map(StockBalance::getAvailableQuantity).map(this::nvl).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private ReconciliationResult result(
        NonMovingStockReconciliationStatus status,
        BigDecimal liveAvailableQuantity,
        String message
    ) {
        return new ReconciliationResult(status, liveAvailableQuantity, message);
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record ReconciliationResult(
        NonMovingStockReconciliationStatus status,
        BigDecimal liveAvailableQuantity,
        String message
    ) {
        public boolean ready() {
            return status == NonMovingStockReconciliationStatus.READY;
        }
    }
}
