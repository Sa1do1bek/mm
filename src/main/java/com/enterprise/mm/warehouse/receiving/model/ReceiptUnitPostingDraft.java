package com.enterprise.mm.warehouse.receiving.model;

public record ReceiptUnitPostingDraft(
    String manufacturerSerialNumber,
    String condition,
    String qualityDecision,
    String notes
) {
}
