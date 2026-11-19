package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record SupplierQuotationSearchRequest(
    String search,
    List<String> status,
    UUID supplierId,
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
    UUID rfqId,
    BigDecimal totalAmountMin,
    BigDecimal totalAmountMax,
    List<String> currencyCode,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime submittedAtFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime submittedAtTo,
    Boolean selected,
    BigDecimal technicalScoreMin,
    BigDecimal technicalScoreMax,
    BigDecimal deliveryScoreMin,
    BigDecimal deliveryScoreMax,
    BigDecimal priceScoreMin,
    BigDecimal priceScoreMax,
    BigDecimal totalScoreMin,
    BigDecimal totalScoreMax,
    String selectionReason
) implements SearchCriteria {
    public static SupplierQuotationSearchRequest empty() {
        return new SupplierQuotationSearchRequest(
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
            null
        );
    }
}
