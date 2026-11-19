package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record ContractSearchRequest(
    String search,
    List<String> status,
    UUID supplierId,
    UUID purchaseOrderId,
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
    String contractNumber,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDateTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDateTo,
    BigDecimal limitAmountMin,
    BigDecimal limitAmountMax,
    BigDecimal usedAmountMin,
    BigDecimal usedAmountMax,
    List<String> currencyCode
) implements SearchCriteria {
    public static ContractSearchRequest empty() {
        return new ContractSearchRequest(
            null,
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
            null
        );
    }
}
