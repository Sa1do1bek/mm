package com.enterprise.mm.service;

import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.InventoryCostLayer;
import com.enterprise.mm.enums.PickingMethod;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;

/** Selection order is independent of the order in which database rows are locked. */
public final class StockPickingOrder {
    private StockPickingOrder() {}

    public static Comparator<StockBalance> balances(PickingMethod method) {
        Comparator<LocalDate> dates = method == PickingMethod.LIFO ? Comparator.reverseOrder() : Comparator.naturalOrder();
        Comparator<LocalDateTime> times = timestamps(method);
        return Comparator.comparing((StockBalance b) -> b.getStockBatch() == null ? null : b.getStockBatch().getReceivedDate(), Comparator.nullsLast(dates))
            .thenComparing(b -> b.getStockBatch() == null ? null : b.getStockBatch().getCreatedAt(), Comparator.nullsLast(times))
            .thenComparing(StockBalance::getStockBatchId, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(StockBalance::getId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    public static Comparator<InventoryCostLayer> costLayers(PickingMethod method) {
        Comparator<LocalDateTime> times = timestamps(method);
        return Comparator.comparing(InventoryCostLayer::getReceivedAt, Comparator.nullsLast(times))
            .thenComparing(InventoryCostLayer::getCreatedAt, Comparator.nullsLast(times))
            .thenComparing(InventoryCostLayer::getId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static Comparator<LocalDateTime> timestamps(PickingMethod method) {
        return method == PickingMethod.LIFO ? Comparator.reverseOrder() : Comparator.naturalOrder();
    }
}
