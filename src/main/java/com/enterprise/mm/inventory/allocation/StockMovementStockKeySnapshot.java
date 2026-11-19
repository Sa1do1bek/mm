package com.enterprise.mm.inventory.allocation;

import java.util.UUID;

public record StockMovementStockKeySnapshot(
    UUID warehouseId,
    UUID storageLocationId,
    UUID storageBinId,
    UUID stockBatchId,
    String batchNumber,
    String disposition
) {
    private static final StockMovementStockKeySnapshot EMPTY =
        new StockMovementStockKeySnapshot(null, null, null, null, null, null);

    public static StockMovementStockKeySnapshot empty() {
        return EMPTY;
    }
}
