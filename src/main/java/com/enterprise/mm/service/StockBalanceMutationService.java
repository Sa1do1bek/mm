package com.enterprise.mm.service;

import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.InsufficientStockException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
public class StockBalanceMutationService {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private ExactOpeningImportGuard openingImportGuard;

    private void guard(StockBalance balance) {
        if (openingImportGuard != null) openingImportGuard.assertOperationsAllowed(balance.getCompanyId());
    }

    /**
     * TODO "Freshness (balanceAt)": every method below changes the persisted balance
     * numbers, so every method must also stamp balanceAt. Previously none of them did,
     * which meant InventoryAvailabilityQueryRepository's staleBalance filter and
     * overview().staleDataCount() could never reflect a real movement — balanceAt just
     * sat wherever it was left (usually null) regardless of how recently stock moved.
     */
    public void reserve(StockBalance balance, BigDecimal quantity) {
        guard(balance);
        BigDecimal normalizedQuantity = requirePositive(quantity, "Reservation quantity must be positive");
        if (nvl(balance.getAvailableQuantity()).compareTo(normalizedQuantity) < 0) {
            throw insufficientStock(balance, normalizedQuantity, "Not enough available stock to reserve");
        }
        balance.setAvailableQuantity(nvl(balance.getAvailableQuantity()).subtract(normalizedQuantity));
        balance.setReservedQuantity(nvl(balance.getReservedQuantity()).add(normalizedQuantity));
        touch(balance);
    }

    public void releaseReservation(StockBalance balance, BigDecimal quantity) {
        guard(balance);
        BigDecimal normalizedQuantity = requirePositive(quantity, "Release quantity must be positive");
        if (nvl(balance.getReservedQuantity()).compareTo(normalizedQuantity) < 0) {
            throw new BusinessException("Reserved stock balance is lower than release quantity");
        }
        balance.setReservedQuantity(nvl(balance.getReservedQuantity()).subtract(normalizedQuantity));
        balance.setAvailableQuantity(nvl(balance.getAvailableQuantity()).add(normalizedQuantity));
        touch(balance);
    }

    public BalanceMutation increaseOnHand(StockBalance balance, BigDecimal quantity, BigDecimal unitCost) {
        return increaseOnHand(balance, quantity, unitCost, null);
    }

    public BalanceMutation increaseOnHand(StockBalance balance, BigDecimal quantity, BigDecimal unitCost, BigDecimal totalValue) {
        guard(balance);
        BigDecimal normalizedQuantity = requirePositive(quantity, "Movement quantity must be positive");
        BalanceSnapshot before = snapshot(balance);
        balance.setQuantity(nvl(balance.getQuantity()).add(normalizedQuantity));
        balance.setAvailableQuantity(nvl(balance.getAvailableQuantity()).add(normalizedQuantity));
        BigDecimal valueToAdd = totalValue != null
            ? totalValue
            : nvl(unitCost).multiply(normalizedQuantity);
        balance.setTotalValue(nvl(balance.getTotalValue()).add(valueToAdd));
        touch(balance);
        return new BalanceMutation(before, snapshot(balance));
    }

    public void moveAvailableToQuarantine(StockBalance balance, BigDecimal quantity) {
        guard(balance);
        BigDecimal normalizedQuantity = requirePositive(quantity, "Quarantine quantity must be positive");
        if (nvl(balance.getAvailableQuantity()).compareTo(normalizedQuantity) < 0) {
            throw insufficientStock(balance, normalizedQuantity);
        }
        balance.setAvailableQuantity(nvl(balance.getAvailableQuantity()).subtract(normalizedQuantity));
        balance.setQuarantineQuantity(nvl(balance.getQuarantineQuantity()).add(normalizedQuantity));
        touch(balance);
    }

    public BalanceMutation decreaseOnHand(
        StockBalance balance,
        BigDecimal quantity,
        BigDecimal consumedCost,
        boolean allowNegativeStock
    ) {
        guard(balance);
        BigDecimal normalizedQuantity = requirePositive(quantity, "Movement quantity must be positive");
        BalanceSnapshot before = snapshot(balance);
        BigDecimal nextAvailableQuantity = nvl(balance.getAvailableQuantity()).subtract(normalizedQuantity);
        if (!allowNegativeStock && nextAvailableQuantity.signum() < 0) {
            throw insufficientStock(balance, normalizedQuantity);
        }
        BigDecimal nextQuantity = nvl(balance.getQuantity()).subtract(normalizedQuantity);
        if (!allowNegativeStock && nextQuantity.signum() < 0) {
            throw new BusinessException("Negative stock is not allowed");
        }
        balance.setQuantity(nextQuantity);
        balance.setAvailableQuantity(nextAvailableQuantity);
        BigDecimal nextTotalValue = nvl(balance.getTotalValue()).subtract(nvl(consumedCost)).max(BigDecimal.ZERO);
        if (nextQuantity.signum() == 0 && nvl(balance.getReservedQuantity()).signum() <= 0 && nvl(balance.getQuarantineQuantity()).signum() <= 0) {
            nextTotalValue = BigDecimal.ZERO;
        }
        balance.setTotalValue(nextTotalValue);
        touch(balance);
        return new BalanceMutation(before, snapshot(balance));
    }

    public BalanceMutation applyExactDelta(
        StockBalance balance,
        BigDecimal onHandDelta,
        BigDecimal availableDelta,
        BigDecimal reservedDelta,
        BigDecimal quarantineDelta,
        BigDecimal totalValueDelta
    ) {
        guard(balance);
        BalanceSnapshot before = snapshot(balance);
        BalanceSnapshot after = new BalanceSnapshot(
            before.onHand().add(nvl(onHandDelta)),
            before.available().add(nvl(availableDelta)),
            before.reserved().add(nvl(reservedDelta)),
            before.quarantine().add(nvl(quarantineDelta)),
            before.totalValue().add(nvl(totalValueDelta))
        );
        if (after.onHand().signum() < 0
            || after.available().signum() < 0
            || after.reserved().signum() < 0
            || after.quarantine().signum() < 0
            || after.totalValue().signum() < 0) {
            throw new BusinessException(
                "Stock exact reversal would produce a negative persisted balance");
        }
        balance.setQuantity(after.onHand());
        balance.setAvailableQuantity(after.available());
        balance.setReservedQuantity(after.reserved());
        balance.setQuarantineQuantity(after.quarantine());
        balance.setTotalValue(after.totalValue());
        touch(balance);
        return new BalanceMutation(before, after);
    }

    private void touch(StockBalance balance) {
        balance.setBalanceAt(LocalDateTime.now());
    }

    private BigDecimal requirePositive(BigDecimal quantity, String message) {
        BigDecimal normalizedQuantity = nvl(quantity);
        if (normalizedQuantity.signum() <= 0) {
            throw new BusinessException(message);
        }
        return normalizedQuantity;
    }

    private InsufficientStockException insufficientStock(StockBalance balance, BigDecimal requestedQuantity) {
        return insufficientStock(balance, requestedQuantity, "Insufficient available stock");
    }

    private InsufficientStockException insufficientStock(
        StockBalance balance,
        BigDecimal requestedQuantity,
        String message
    ) {
        String unit = balance.getMaterial() == null || balance.getMaterial().getUnitOfMeasure() == null
            ? null
            : firstNonBlank(
                balance.getMaterial().getUnitOfMeasure().getMeasurementNameShort(),
                balance.getMaterial().getUnitOfMeasure().getMeasurementNameShortRu(),
                balance.getMaterial().getUnitOfMeasure().getName()
            );
        return new InsufficientStockException(
            message,
            balance.getMaterialId(),
            balance.getMaterial() == null ? null : balance.getMaterial().getName(),
            balance.getWarehouseId(),
            balance.getWarehouse() == null ? null : balance.getWarehouse().getName(),
            requestedQuantity,
            nvl(balance.getAvailableQuantity()),
            unit
        );
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BalanceSnapshot snapshot(StockBalance balance) {
        return new BalanceSnapshot(
            nvl(balance.getQuantity()),
            nvl(balance.getAvailableQuantity()),
            nvl(balance.getReservedQuantity()),
            nvl(balance.getQuarantineQuantity()),
            nvl(balance.getTotalValue())
        );
    }

    public record BalanceSnapshot(
        BigDecimal onHand,
        BigDecimal available,
        BigDecimal reserved,
        BigDecimal quarantine,
        BigDecimal totalValue
    ) {
    }

    public record BalanceMutation(BalanceSnapshot before, BalanceSnapshot after) {
        public BigDecimal onHandDelta() {
            return after.onHand().subtract(before.onHand());
        }

        public BigDecimal availableDelta() {
            return after.available().subtract(before.available());
        }

        public BigDecimal reservedDelta() {
            return after.reserved().subtract(before.reserved());
        }

        public BigDecimal quarantineDelta() {
            return after.quarantine().subtract(before.quarantine());
        }

        public BigDecimal totalValueDelta() {
            return after.totalValue().subtract(before.totalValue());
        }
    }
}
