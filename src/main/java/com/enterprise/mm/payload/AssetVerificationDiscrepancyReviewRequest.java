package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record AssetVerificationDiscrepancyReviewRequest(
    @NotNull Long expectedVersion,
    List<ReviewedDiscrepancy> discrepancies
) {
    public record ReviewedDiscrepancy(UUID assetVerificationLineId, String resolutionStatus, String comment) {
    }
}
