package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;

public record AssetInventoryNumberAssignRequest(
    @NotNull Long expectedVersion,
    String inventoryNumber,
    String reason
) {
}
