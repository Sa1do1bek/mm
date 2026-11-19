package com.enterprise.mm.warehouse.transfergroup;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record TransferGroupCreateRequest(
    String code,
    String name,
    String description,
    UUID sourceWarehouseId,
    UUID sourceLocationId,
    LocalDate plannedDate,
    List<DestinationPlan> destinations
) {
    public record DestinationPlan(
        UUID destinationWarehouseId,
        UUID destinationLocationId,
        List<MaterialLine> items
    ) {}

    public record MaterialLine(
        UUID materialId,
        BigDecimal plannedQuantity,
        UUID stockBatchId,
        UUID storageLocationId
    ) {}
}
