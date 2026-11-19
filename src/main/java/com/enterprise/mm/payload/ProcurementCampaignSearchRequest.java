package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record ProcurementCampaignSearchRequest(
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
    String campaignNumber,
    UUID procurementPlanId,
    UUID categoryStrategyId,
    UUID materialGroupId,
    UUID rfqId,
    UUID purchaseRequestId,
    UUID methodDecisionId,
    UUID procurementProcedureId,
    List<String> campaignType,
    List<String> procedureType,
    Integer targetSupplierCount,
    BigDecimal plannedSpendAmountMin,
    BigDecimal plannedSpendAmountMax,
    BigDecimal approvedSpendAmountMin,
    BigDecimal approvedSpendAmountMax,
    BigDecimal savingsTargetAmountMin,
    BigDecimal savingsTargetAmountMax,
    List<String> currencyCode,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate plannedStartDateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate plannedStartDateTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate plannedEndDateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate plannedEndDateTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime launchedAtFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime launchedAtTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime completedAtFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime completedAtTo
) implements SearchCriteria {
    public static ProcurementCampaignSearchRequest empty() {
        return new ProcurementCampaignSearchRequest(
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
            null,
            null
        );
    }
}
