package com.enterprise.mm.payload;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
public record InventoryAdjustmentPostRequest(
    @NotNull Long expectedVersion,
    @NotNull Long approvedCountRevision,
    @NotNull LocalDate postingDate,
    List<Line> lines
) {
    public InventoryAdjustmentPostRequest(Long expectedVersion, Long approvedCountRevision, LocalDate postingDate) {
        this(expectedVersion, approvedCountRevision, postingDate, List.of());
    }
    public record Line(UUID inventoryItemId, List<UUID> materialUnitIds) {}
}
