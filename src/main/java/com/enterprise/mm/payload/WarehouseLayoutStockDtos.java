package com.enterprise.mm.payload;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class WarehouseLayoutStockDtos {
    private WarehouseLayoutStockDtos() {}
    public enum Precision { BIN, LOCATION, WAREHOUSE }
    public record LocationStock(UUID storageLocationId, String storageLocationName,
        UUID storageBinId, String storageBinName, long positionCount, Precision precision, LocalDateTime balanceAt) {}
    public record Snapshot(Instant asOf, List<LocationStock> locations) {}
}
