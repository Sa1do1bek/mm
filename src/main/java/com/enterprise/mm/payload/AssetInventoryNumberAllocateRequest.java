package com.enterprise.mm.payload;

import jakarta.validation.constraints.Min;

public record AssetInventoryNumberAllocateRequest(
    @Min(1) int count,
    String source
) {
}
