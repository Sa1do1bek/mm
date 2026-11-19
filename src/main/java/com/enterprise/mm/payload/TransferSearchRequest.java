package com.enterprise.mm.payload;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record TransferSearchRequest(
    String search,
    List<String> status,
    UUID warehouseId,
    UUID sourceWarehouseId,
    UUID destinationWarehouseId,
    LocalDate dateFrom,
    LocalDate dateTo,
    String action
) {
    public TransferSearchRequest(String search, List<String> status, UUID warehouseId, UUID sourceWarehouseId, UUID destinationWarehouseId, LocalDate dateFrom, LocalDate dateTo) {
        this(search,status,warehouseId,sourceWarehouseId,destinationWarehouseId,dateFrom,dateTo,null);
    }
}
