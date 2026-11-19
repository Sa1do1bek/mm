package com.enterprise.mm.payload;

import java.util.UUID;

public record AssetAcceptanceSearchRequest(
    UUID purchaseOrderId,
    String approvalStatus,
    String postingStatus
) {
}
