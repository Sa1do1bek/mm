package com.enterprise.mm.payload;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ManagementOpportunityRunSearchRequest(
    String status,
    String requestedType,
    String targetCurrencyCode,
    LocalDate asOfDateFrom,
    LocalDate asOfDateTo,
    LocalDateTime startedAtFrom,
    LocalDateTime startedAtTo,
    Boolean hasSkipped,
    Boolean hasFailure,
    boolean includeDeleted
) {
}
