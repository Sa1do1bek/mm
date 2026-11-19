package com.enterprise.mm.service;

import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.InsufficientStockException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

@Service
public class ReservationStockMutationService {
    public Mutation reserve(StockBalance balance, BigDecimal requested) {
        BigDecimal quantity = positive(balance, requested);
        if (nvl(balance.getAvailableQuantity()).compareTo(quantity) < 0) {
            throw insufficient(balance, quantity, "Not enough available stock to reserve");
        }
        balance.setAvailableQuantity(nvl(balance.getAvailableQuantity()).subtract(quantity));
        balance.setReservedQuantity(nvl(balance.getReservedQuantity()).add(quantity));
        return new Mutation(BigDecimal.ZERO, quantity.negate(), quantity, BigDecimal.ZERO);
    }

    public Mutation release(StockBalance balance, BigDecimal requested) {
        BigDecimal quantity = positive(balance, requested);
        requireReserved(balance, quantity);
        balance.setAvailableQuantity(nvl(balance.getAvailableQuantity()).add(quantity));
        balance.setReservedQuantity(nvl(balance.getReservedQuantity()).subtract(quantity));
        return new Mutation(BigDecimal.ZERO, quantity, quantity.negate(), BigDecimal.ZERO);
    }

    public Mutation consume(StockBalance balance, BigDecimal requested) {
        BigDecimal quantity = positive(balance, requested);
        requireReserved(balance, quantity);
        if (nvl(balance.getQuantity()).compareTo(quantity) < 0) {
            throw new BusinessException("On-hand stock is lower than reservation consumption quantity");
        }
        BigDecimal valueDelta = proportionalValue(balance, quantity).negate();
        balance.setQuantity(nvl(balance.getQuantity()).subtract(quantity));
        balance.setReservedQuantity(nvl(balance.getReservedQuantity()).subtract(quantity));
        balance.setTotalValue(nvl(balance.getTotalValue()).add(valueDelta).max(BigDecimal.ZERO));
        return new Mutation(quantity.negate(), BigDecimal.ZERO, quantity.negate(), valueDelta);
    }

    private BigDecimal proportionalValue(StockBalance balance, BigDecimal quantity) {
        BigDecimal onHand = nvl(balance.getQuantity());
        if (onHand.signum() <= 0 || nvl(balance.getTotalValue()).signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (quantity.compareTo(onHand) == 0) {
            return nvl(balance.getTotalValue());
        }
        return nvl(balance.getTotalValue())
            .multiply(quantity)
            .divide(onHand, 4, RoundingMode.HALF_UP);
    }

    private void requireReserved(StockBalance balance, BigDecimal quantity) {
        if (nvl(balance.getReservedQuantity()).compareTo(quantity) < 0) {
            throw new BusinessException("Reserved stock balance is lower than reservation quantity");
        }
    }

    private BigDecimal positive(StockBalance balance, BigDecimal value) {
        BigDecimal normalized = nvl(value);
        if (normalized.signum() <= 0) {
            throw new BusinessException("Reservation lifecycle quantity must be positive");
        }
        if (balance.getMaterial() != null) StockUomPolicy.quantity(balance.getMaterial(), normalized, false);
        else if (normalized.stripTrailingZeros().scale() > StockUomPolicy.MAX_QUANTITY_SCALE
            || normalized.precision() - normalized.scale() > 15)
            throw new BusinessException("Reservation quantity exceeds stock quantity precision");
        return normalized;
    }

    private InsufficientStockException insufficient(StockBalance balance, BigDecimal quantity, String message) {
        return new InsufficientStockException(
            message,
            balance.getMaterialId(),
            balance.getMaterial() == null ? null : balance.getMaterial().getName(),
            balance.getWarehouseId(),
            balance.getWarehouse() == null ? null : balance.getWarehouse().getName(),
            quantity,
            nvl(balance.getAvailableQuantity()),
            null
        );
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record Mutation(
        BigDecimal onHandDelta,
        BigDecimal availableDelta,
        BigDecimal reservedDelta,
        BigDecimal totalValueDelta
    ) {
    }
}
