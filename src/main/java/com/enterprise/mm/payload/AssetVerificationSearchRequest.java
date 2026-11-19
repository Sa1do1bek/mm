package com.enterprise.mm.payload;

import java.util.UUID;

public record AssetVerificationSearchRequest(
    UUID warehouseId,
    UUID departmentId,
    String status,
    String verificationType
) {
}
