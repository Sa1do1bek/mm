package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record ConsolidatedDemandSearchRequest(
    String search,
    List<String> status,
    List<UUID> materialIds,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    boolean includeDeleted,
    String code,
    String name,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdAtFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdAtTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime updatedAtFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime updatedAtTo,
    String createdBy,
    String updatedBy,
    UUID demandPlanId,
    UUID materialId,
    UUID budgetItemId,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStartFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStartTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEndFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEndTo,
    List<String> highestPriority,
    BigDecimal totalRequestedQuantityMin,
    BigDecimal totalRequestedQuantityMax,
    BigDecimal totalApprovedQuantityMin,
    BigDecimal totalApprovedQuantityMax,
    BigDecimal totalEstimatedAmountMin,
    BigDecimal totalEstimatedAmountMax,
    List<String> currencyCode,
    Integer sourceItemCount,
    UUID purchaseRequestId,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime consolidatedAtFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime consolidatedAtTo
) implements SearchCriteria {
    public static ConsolidatedDemandSearchRequest empty() {
        return new ConsolidatedDemandSearchRequest(
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }
}
