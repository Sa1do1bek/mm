package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record FixedAssetEquipmentLinkRequest(
    @NotNull Long expectedVersion,
    @NotNull UUID equipmentId,
    String reason
) {
}
