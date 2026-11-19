package com.enterprise.mm.warehouse.transfergroup;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record TransferGroupResponse(
    UUID id,
    String code,
    String name,
    String description,
    String status,
    UUID sourceWarehouseId,
    LocalDate plannedDate,
    List<UUID> childTransferIds,
    List<ItemDetail> items,
    TransferGroupAggregateProgress aggregateProgress,
    LocalDateTime createdAt
) {
    public record ItemDetail(
        UUID id,
        UUID destinationWarehouseId,
        UUID materialId,
        java.math.BigDecimal plannedQuantity,
        UUID childTransferId,
        UUID childTransferLineId,
        String status
    ) {}
}
