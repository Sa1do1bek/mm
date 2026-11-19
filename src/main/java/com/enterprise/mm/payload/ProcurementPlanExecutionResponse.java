package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ProcurementPlanExecutionResponse(
    UUID planId,
    String planNumber,
    String status,
    Integer planYear,
    String currencyCode,
    BigDecimal plannedAmount,
    BigDecimal approvedBudgetAmount,
    BigDecimal orderedAmount,
    BigDecimal acceptedAmount,
    BigDecimal openOrderAmount,
    BigDecimal remainingBudgetAmount,
    BigDecimal executionPercent,
    int matchedOrderCount,
    int matchedLineCount,
    List<String> warnings,
    List<OrderLine> lines
) {
    public record OrderLine(
        UUID orderId,
        String orderCode,
        String orderStatus,
        LocalDate orderDate,
        UUID lineId,
        UUID materialId,
        String materialCode,
        String materialName,
        BigDecimal orderedQuantity,
        BigDecimal acceptedQuantity,
        BigDecimal unitPrice,
        BigDecimal orderedAmount,
        BigDecimal acceptedAmount,
        String currencyCode
    ) {}
}
