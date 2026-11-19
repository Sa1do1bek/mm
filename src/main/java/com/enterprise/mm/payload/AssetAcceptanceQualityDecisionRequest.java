package com.enterprise.mm.payload;

import java.util.List;
import java.util.UUID;

public record AssetAcceptanceQualityDecisionRequest(
    Long expectedVersion,
    List<UnitDecision> units
) {
    public record UnitDecision(UUID assetAcceptanceUnitId, String qualityDecision, String reason) {
    }
}
