package com.enterprise.mm.warehouse.receiving.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ReceiptPostingResult(
    UUID receiptId,
    String receiptCode,
    BigDecimal acceptedQuantity,
    BigDecimal rejectedQuantity,
    BigDecimal damagedQuantity,
    BigDecimal excessQuantity,
    BigDecimal shortageQuantity,
    int materialUnitsCreated,
    int receiptUnitsRecorded,
    List<UUID> approvedAccreditationExceptionIds
) {
    public ReceiptPostingResult(
        UUID receiptId,
        String receiptCode,
        BigDecimal acceptedQuantity,
        BigDecimal rejectedQuantity,
        BigDecimal damagedQuantity,
        BigDecimal excessQuantity,
        BigDecimal shortageQuantity,
        List<UUID> approvedAccreditationExceptionIds
    ) {
        this(
            receiptId,
            receiptCode,
            acceptedQuantity,
            rejectedQuantity,
            damagedQuantity,
            excessQuantity,
            shortageQuantity,
            0,
            0,
            approvedAccreditationExceptionIds
        );
    }
}
