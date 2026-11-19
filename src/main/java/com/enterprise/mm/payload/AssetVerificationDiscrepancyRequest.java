package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AssetVerificationDiscrepancyRequest(
    @NotNull Long expectedVersion,
    UUID fixedAssetId,
    String discrepancyType,
    String reason
) {
}
