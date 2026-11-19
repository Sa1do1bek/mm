package com.enterprise.mm.enums;

/** Result status derived from the persisted availability flag. */
public enum StockValuationRunItemStatus {
    COMPLETED,
    FAILED;

    public static StockValuationRunItemStatus fromAvailable(boolean available) {
        return available ? COMPLETED : FAILED;
    }

    public boolean isAvailable() {
        return this == COMPLETED;
    }
}
