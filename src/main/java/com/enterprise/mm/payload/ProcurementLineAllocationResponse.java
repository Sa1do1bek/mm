package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.util.UUID;

public record ProcurementLineAllocationResponse(
    UUID id,
    String sourceDocumentType,
    UUID sourceDocumentId,
    UUID sourceLineId,
    String targetDocumentType,
    UUID targetDocumentId,
    UUID targetLineId,
    BigDecimal allocatedQuantity,
    BigDecimal allocatedAmount,
    BigDecimal cancelledQuantity,
    BigDecimal reversedQuantity,
    BigDecimal remainingQuantity,
    String currencyCode,
    UUID supplierId,
    String idempotencyKey
) {
}
