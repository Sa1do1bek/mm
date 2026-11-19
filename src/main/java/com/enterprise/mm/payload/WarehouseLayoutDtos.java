package com.enterprise.mm.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class WarehouseLayoutDtos {
    private WarehouseLayoutDtos() {}

    public enum Kind { RACK, ZONE, PALLET, OBSTACLE }

    public record Item(
        @NotBlank @Size(max = 80) String id,
        @NotNull Kind kind,
        @NotBlank @Size(max = 120) String label,
        @DecimalMin("0") @DecimalMax("500") double x,
        @DecimalMin("0") @DecimalMax("500") double y,
        @DecimalMin("0.1") @DecimalMax("500") double width,
        @DecimalMin("0.1") @DecimalMax("500") double depth,
        @DecimalMin("0.05") @DecimalMax("50") double height,
        @Min(0) @Max(270) int rotation,
        UUID storageLocationId,
        UUID storageBinId
    ) {}

    public record Layout(
        @DecimalMin("1") @DecimalMax("500") double width,
        @DecimalMin("1") @DecimalMax("500") double depth,
        @DecimalMin("1") @DecimalMax("50") double height,
        @NotNull @Size(max = 500) List<@NotNull @Valid Item> items
    ) {}

    public record UpdateRequest(@PositiveOrZero Long version, @NotNull @Valid Layout layout) {}
    public record Response(UUID warehouseId, Long version, Layout layout, LocalDateTime updatedAt) {}
}
