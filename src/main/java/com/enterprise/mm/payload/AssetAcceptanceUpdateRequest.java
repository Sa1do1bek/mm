package com.enterprise.mm.payload;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record AssetAcceptanceUpdateRequest(
    Long expectedVersion,
    UUID supplierId,
    UUID asnId,
    String deliveryReference,
    UUID warehouseId,
    UUID storageLocationId,
    UUID receivedByEmployeeId,
    LocalDate receiptDate,
    LocalDateTime receivedAt,
    String currencyCode,
    String exchangeRateSource,
    String sourceEvidence,
    List<AssetAcceptanceLineRequest> lines
) {
}
