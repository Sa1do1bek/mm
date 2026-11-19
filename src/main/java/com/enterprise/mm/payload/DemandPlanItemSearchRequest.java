package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record DemandPlanItemSearchRequest(
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
    UUID departmentId,
    UUID materialId,
    UUID budgetItemId,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate requiredDateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate requiredDateTo,
    List<String> demandType,
    List<String> priority,
    String projectCode,
    String repairOrderCode,
    BigDecimal requestedQuantityMin,
    BigDecimal requestedQuantityMax,
    BigDecimal approvedQuantityMin,
    BigDecimal approvedQuantityMax,
    BigDecimal reservedQuantityMin,
    BigDecimal reservedQuantityMax,
    BigDecimal estimatedUnitPriceMin,
    BigDecimal estimatedUnitPriceMax,
    BigDecimal estimatedAmountMin,
    BigDecimal estimatedAmountMax,
    List<String> currencyCode,
    UUID consolidatedDemandId,
    UUID purchaseRequestItemId
) implements SearchCriteria {
    public static DemandPlanItemSearchRequest empty() {
        return new DemandPlanItemSearchRequest(
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
            null,
            null,
            null,
            null,
            null
        );
    }
}
