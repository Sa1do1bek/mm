package com.enterprise.mm.payload;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ServiceExecutionSearchRequest(
    String search,
    List<String> status,
    UUID serviceRequestId,
    UUID serviceOrderId,
    UUID responsibleUserId,
    LocalDate plannedStartFrom,
    LocalDate plannedStartTo,
    LocalDate plannedEndFrom,
    LocalDate plannedEndTo,
    boolean overdueOnly,
    boolean includeDeleted
) {
}
