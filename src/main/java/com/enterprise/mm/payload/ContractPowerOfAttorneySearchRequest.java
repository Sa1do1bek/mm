package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record ContractPowerOfAttorneySearchRequest(
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
    String attorneyNumber,
    String issuedTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issueDateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issueDateTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validToFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validToTo,
    BigDecimal amountLimitMin,
    BigDecimal amountLimitMax
) implements SearchCriteria {
    public static ContractPowerOfAttorneySearchRequest empty() {
        return new ContractPowerOfAttorneySearchRequest(
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
            null
        );
    }
}
