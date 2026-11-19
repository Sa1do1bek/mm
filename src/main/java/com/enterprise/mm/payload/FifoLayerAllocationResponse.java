package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record FifoLayerAllocationResponse(UUID movementId, List<Allocation> allocations) {
    public record Allocation(
        UUID receiptLayerId,
        UUID receiptDocumentId,
        LocalDate receiptDate,
        BigDecimal quantityAllocated,
        BigDecimal unitCost,
        BigDecimal totalCost,
        BigDecimal remainingLayerQuantity
    ) {
    }
}
