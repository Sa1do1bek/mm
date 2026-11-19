package com.enterprise.mm.payload;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record AssetDepreciationRunRequest(
    @NotNull @Min(2000) @Max(2100) Integer year,
    @NotNull @Min(1) @Max(12) Integer month,
    List<UUID> assetIds,
    String note
) {
}
