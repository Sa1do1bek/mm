package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record ContractReconciliationActSearchRequest(
    String search,
    List<String> status,
    UUID supplierId,
    UUID contractId,
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
    String actNumber,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStartFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStartTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEndFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEndTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate actDateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate actDateTo,
    BigDecimal openingBalanceMin,
    BigDecimal openingBalanceMax,
    BigDecimal debitAmountMin,
    BigDecimal debitAmountMax,
    BigDecimal creditAmountMin,
    BigDecimal creditAmountMax,
    BigDecimal closingBalanceMin,
    BigDecimal closingBalanceMax
) implements SearchCriteria {
    public static ContractReconciliationActSearchRequest empty() {
        return new ContractReconciliationActSearchRequest(
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
            null,
            null,
            null,
            null,
            null,
            null
        );
    }
}
