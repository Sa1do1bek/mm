package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.enums.RequestType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record PurchaseRequestSearchRequest(
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
    UUID departmentId,
    UUID demandPlanId,
    String requestedBy,
    List<RequestType> requestType,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate requiredDateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate requiredDateTo,
    BigDecimal totalAmountMin,
    BigDecimal totalAmountMax,
    List<String> currencyCode
) implements SearchCriteria {
    public static PurchaseRequestSearchRequest empty() {
        return new PurchaseRequestSearchRequest(
            null, null, null, null, false, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null
        );
    }
}
