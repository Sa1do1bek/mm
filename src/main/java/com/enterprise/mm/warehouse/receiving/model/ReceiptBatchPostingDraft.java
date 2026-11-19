package com.enterprise.mm.warehouse.receiving.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ReceiptBatchPostingDraft(
    String batchKey,
    UUID stockMovementId,
    UUID stockBatchId,
    String manufacturerBatchNumber,
    LocalDate manufacturedOn,
    LocalDate expiresOn,
    BigDecimal acceptedQuantity,
    BigDecimal rejectedQuantity,
    BigDecimal damagedQuantity,
    List<ReceiptUnitPostingDraft> units
) {
    public ReceiptBatchPostingDraft {
        units = units == null ? List.of() : List.copyOf(units);
    }
}
