package com.enterprise.mm.payload;

import java.util.UUID;

public record AssetVerificationCreateRequest(
    UUID warehouseId,
    UUID departmentId,
    String verificationType
) {
}
