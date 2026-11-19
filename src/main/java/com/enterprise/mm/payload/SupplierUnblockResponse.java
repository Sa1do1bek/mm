package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.util.UUID;

public record SupplierUnblockResponse(
    UUID requestId,
    UUID supplierId,
    UUID purchaseOrderId,
    String reason,
    UUID requestedBy,
    String status,
    UUID reviewedBy,
    LocalDateTime reviewedAt,
    String reviewComment,
    UUID decidedBy,
    LocalDateTime decidedAt,
    String decisionComment,
    Long version,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
