package com.enterprise.mm.payload;

import com.enterprise.mm.enums.ManagementOpportunityDataCompleteness;
import com.enterprise.mm.enums.ManagementOpportunityConfidence;
import com.enterprise.mm.enums.ManagementOpportunityType;
import java.time.LocalDate;
import java.util.UUID;

public record ManagementOpportunitySearchRequest(
    ManagementOpportunityType type,
    String status,
    ManagementOpportunityDataCompleteness completeness,
    String sourceAggregateType,
    UUID sourceAggregateId,
    UUID supplierId,
    UUID materialId,
    UUID warehouseId,
    ManagementOpportunityConfidence confidence,
    String assignedRole,
    LocalDate asOfDateFrom,
    LocalDate asOfDateTo,
    boolean includeArchived
) {
}
