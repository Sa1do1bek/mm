package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;

public record AssetAcceptancePostRequest(
    @NotNull Long expectedVersion,
    boolean assignInventoryNumbers,
    String reason
) {
}
