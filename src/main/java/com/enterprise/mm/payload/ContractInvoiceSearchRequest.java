package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record ContractInvoiceSearchRequest(
    String search,
    List<String> status,
    UUID supplierId,
    UUID contractId,
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
    String invoiceNumber,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate invoiceDateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate invoiceDateTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDateTo,
    BigDecimal amountMin,
    BigDecimal amountMax,
    List<String> currencyCode
) implements SearchCriteria {
    public static ContractInvoiceSearchRequest empty() {
        return new ContractInvoiceSearchRequest(
            null,
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
