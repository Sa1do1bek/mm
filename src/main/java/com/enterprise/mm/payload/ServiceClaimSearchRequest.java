package com.enterprise.mm.payload;

import java.util.List;
import java.util.UUID;

public record ServiceClaimSearchRequest(
    String search,
    List<String> status,
    UUID serviceRequestId,
    UUID serviceActId,
    UUID serviceOrderId,
    UUID purchaseOrderItemId,
    UUID workCompletionActId,
    String originType,
    UUID supplierId,
    List<String> claimType,
    List<String> severity,
    boolean includeDeleted
) {
}
