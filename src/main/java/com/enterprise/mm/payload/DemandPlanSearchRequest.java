package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;

public record DemandPlanSearchRequest(
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
    Integer planYear,
    List<String> periodType,
    Integer quarter,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStartFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStartTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEndFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEndTo,
    String ownerUsername,
    List<String> currencyCode,
    BigDecimal totalPlannedAmountMin,
    BigDecimal totalPlannedAmountMax,
    BigDecimal totalApprovedAmountMin,
    BigDecimal totalApprovedAmountMax,
    BigDecimal totalReservedQuantityMin,
    BigDecimal totalReservedQuantityMax,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime consolidatedAtFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime consolidatedAtTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime approvedAtFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime approvedAtTo
) implements SearchCriteria {
    public static DemandPlanSearchRequest empty() {
        return new DemandPlanSearchRequest(
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

    public static DemandPlanSearchRequest forPeriodType(SearchCriteria criteria, String periodType) {
        List<String> periodTypes = periodType == null || periodType.isBlank() ? null : List.of(SearchCriteria.normalizedToken(periodType));
        return new DemandPlanSearchRequest(
            null,
            null,
            null,
            null,
            criteria != null && criteria.includeDeleted(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            periodTypes,
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
