package com.enterprise.mm.procurement.lineage;

import com.enterprise.mm.error.BusinessException;
import java.math.BigDecimal;
import java.util.Collection;

/**
 * Shared arithmetic for split awards, partial fulfilment, and cancellation.
 * The caller supplies already-normalized active allocations; this class is
 * deliberately persistence-agnostic so every workflow uses the same rules.
 */
public final class ProcurementAllocationMath {
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private ProcurementAllocationMath() {
    }

    public static BigDecimal remaining(BigDecimal source, Collection<Allocation> allocations) {
        BigDecimal normalizedSource = requireNonNegative(source, "source quantity");
        BigDecimal allocated = allocations == null
            ? ZERO
            : allocations.stream()
                .map(Allocation::effectiveQuantity)
                .map(value -> requireNonNegative(value, "allocated quantity"))
                .reduce(ZERO, BigDecimal::add);
        BigDecimal remaining = normalizedSource.subtract(allocated);
        if (remaining.signum() < 0) {
            throw new BusinessException("Allocated quantity exceeds source quantity");
        }
        return remaining;
    }

    public static void requireAvailable(BigDecimal requested, BigDecimal source, Collection<Allocation> allocations) {
        BigDecimal normalizedRequested = requirePositive(requested, "requested quantity");
        if (normalizedRequested.compareTo(remaining(source, allocations)) > 0) {
            throw new BusinessException("Requested quantity exceeds the remaining source quantity");
        }
    }

    public static BigDecimal requirePositive(BigDecimal value, String label) {
        if (value == null || value.signum() <= 0) {
            throw new BusinessException(label + " must be greater than zero");
        }
        return value;
    }

    private static BigDecimal requireNonNegative(BigDecimal value, String label) {
        if (value == null || value.signum() < 0) {
            throw new BusinessException(label + " cannot be negative");
        }
        return value;
    }

    public record Allocation(BigDecimal quantity, BigDecimal cancelledQuantity, BigDecimal reversedQuantity) {
        public BigDecimal effectiveQuantity() {
            BigDecimal active = requireNonNegative(quantity == null ? ZERO : quantity, "allocated quantity");
            BigDecimal cancelled = requireNonNegative(cancelledQuantity == null ? ZERO : cancelledQuantity, "cancelled quantity");
            BigDecimal reversed = requireNonNegative(reversedQuantity == null ? ZERO : reversedQuantity, "reversed quantity");
            if (cancelled.add(reversed).compareTo(active) > 0) {
                throw new BusinessException("Cancelled and reversed quantity exceeds allocated quantity");
            }
            return active.subtract(cancelled).subtract(reversed).max(ZERO);
        }
    }
}
