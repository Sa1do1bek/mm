package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record ProcurementCategoryStrategySearchRequest(
    String search,
    List<String> status,
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
    UUID materialGroupId,
    Integer strategyYear,
    UUID ownerEmployeeId,
    UUID budgetItemId,
    List<String> strategyType,
    List<String> sourcingApproach,
    String supplierMarket,
    List<String> riskLevel,
    BigDecimal targetSavingsPercentMin,
    BigDecimal targetSavingsPercentMax,
    BigDecimal plannedSpendAmountMin,
    BigDecimal plannedSpendAmountMax,
    BigDecimal approvedSpendLimitMin,
    BigDecimal approvedSpendLimitMax,
    List<String> currencyCode,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validFromFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validFromTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validToFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validToTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime approvedAtFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime approvedAtTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime activatedAtFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime activatedAtTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime closedAtFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime closedAtTo
) implements SearchCriteria {
    public static ProcurementCategoryStrategySearchRequest empty() {
        return new ProcurementCategoryStrategySearchRequest(
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
