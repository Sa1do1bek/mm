package com.enterprise.mm.payload;

import java.util.UUID;

public record ServiceReferenceDTO(
    UUID serviceRequestId,
    UUID serviceOrderId,
    UUID serviceActId,
    UUID purchaseOrderId,
    UUID contractId,
    UUID supplierId,
    UUID budgetItemId,
    String serviceDescription,
    String serviceCategory,
    String unit,
    String period,
    String stage
) {
}
