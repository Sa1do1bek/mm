package com.enterprise.mm.payload;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
public record InventoryAdjustmentPostingResponse(UUID inventoryDocumentId, String status, List<UUID> movementIds,
    Long version, Long approvedCountRevision, LocalDate postingDate, boolean alreadyPosted) {}
