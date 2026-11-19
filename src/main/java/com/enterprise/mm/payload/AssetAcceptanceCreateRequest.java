package com.enterprise.mm.payload;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record AssetAcceptanceCreateRequest(
    UUID contractId,
    UUID purchaseOrderId,
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
    List<AssetAcceptanceLineRequest> lines,
    UUID fakturaPowerOfAttorneyId,
    UUID fakturaInvoiceId,
    String missingFakturaReason
) {
    public AssetAcceptanceCreateRequest(UUID contractId, UUID purchaseOrderId, UUID supplierId, UUID asnId,
        String deliveryReference, UUID warehouseId, UUID storageLocationId, UUID receivedByEmployeeId,
        LocalDate receiptDate, LocalDateTime receivedAt, String currencyCode, String exchangeRateSource,
        String sourceEvidence, List<AssetAcceptanceLineRequest> lines) {
        this(contractId, purchaseOrderId, supplierId, asnId, deliveryReference, warehouseId,
            storageLocationId, receivedByEmployeeId, receiptDate, receivedAt, currencyCode,
            exchangeRateSource, sourceEvidence, lines, null, null, null);
    }
}
