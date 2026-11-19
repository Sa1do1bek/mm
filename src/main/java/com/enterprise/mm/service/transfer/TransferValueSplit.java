package com.enterprise.mm.service.transfer;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class TransferValueSplit {
    private TransferValueSplit() {}
    public static BigDecimal take(BigDecimal remainingQuantity, BigDecimal remainingValue, BigDecimal quantity) {
        if (remainingQuantity == null || remainingValue == null || quantity == null
            || remainingQuantity.signum() <= 0 || remainingValue.signum() < 0
            || quantity.signum() <= 0 || quantity.compareTo(remainingQuantity) > 0)
            throw new IllegalArgumentException("Quantity exceeds unresolved allocation or value is invalid");
        return quantity.compareTo(remainingQuantity) == 0 ? remainingValue
            : remainingValue.multiply(quantity).divide(remainingQuantity, 4, RoundingMode.HALF_UP);
    }
}
