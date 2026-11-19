package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class SupplierAccreditationCommandDtos {
    private SupplierAccreditationCommandDtos() {
    }

    public record VersionedCommand(
        @NotNull Long expectedVersion,
        @Size(max = 1000) String reviewComment
    ) {
    }

    public record DecisionCommand(
        @NotNull Long expectedVersion,
        String decision,
        List<@NotNull UUID> allowedMaterialGroupIds,
        LocalDate validFrom,
        LocalDate validTo,
        @Size(max = 1000) String reviewComment
    ) {
    }
}
