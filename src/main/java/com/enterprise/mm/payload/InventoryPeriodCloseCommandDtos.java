package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class InventoryPeriodCloseCommandDtos {
    private InventoryPeriodCloseCommandDtos() {
    }

    public record VersionedCommand(
        @NotNull Long expectedVersion
    ) {
    }

    public record ReopenRequest(
        @NotNull Long expectedVersion,
        @NotBlank @Size(min = 5, max = 500) String reason
    ) {
    }
}
