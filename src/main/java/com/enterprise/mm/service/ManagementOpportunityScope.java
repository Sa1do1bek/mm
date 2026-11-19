package com.enterprise.mm.service;

import java.util.UUID;

public record ManagementOpportunityScope(
    UUID warehouseId,
    UUID materialGroupId,
    UUID supplierId
) {
}
