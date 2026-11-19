package com.enterprise.mm.warehouse.receiving.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ReceiptPostingDraft(
    UUID purchaseOrderId,
    UUID supplierId,
    UUID asnId,
    String idempotencyKey,
    String currencyCode,
    BigDecimal exchangeRate,
    BigDecimal acceptedAmount,
    BigDecimal baseAcceptedAmount,
    List<UUID> evidenceAttachmentIds,
    List<ReceiptLinePostingDraft> lines
) {
}
