package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ServiceRequestSearchRequest(
    String search,
    List<String> status,
    LocalDate dateFrom,
    LocalDate dateTo,
    UUID departmentId,
    UUID responsibleUserId,
    UUID supplierId,
    UUID contractId,
    UUID purchaseOrderId,
    UUID budgetItemId,
    String category,
    String urgency,
    LocalDate plannedStartFrom,
    LocalDate plannedStartTo,
    LocalDate plannedEndFrom,
    LocalDate plannedEndTo,
    BigDecimal estimatedAmountMin,
    BigDecimal estimatedAmountMax,
    boolean includeDeleted
) {
}
