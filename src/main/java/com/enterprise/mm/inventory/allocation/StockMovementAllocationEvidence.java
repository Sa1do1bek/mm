package com.enterprise.mm.inventory.allocation;

import java.util.List;
import java.util.UUID;

/**
 * Scope observed while the posting mutation was calculated. The writer validates
 * it against the persisted movement and never copies these ownership values into
 * the append-only rows.
 */
public record StockMovementAllocationEvidence(
    String tenantId,
    UUID companyId,
    UUID movementId,
    UUID materialId,
    UUID sourceInventoryItemId,
    List<StockMovementAllocationDraft> allocations
) {
    public StockMovementAllocationEvidence {
        allocations = allocations == null ? List.of() : List.copyOf(allocations);
    }
}
