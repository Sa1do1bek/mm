package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;

public record SupplierSearchRequest(
    String search,
    List<String> status,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    boolean includeDeleted,
    String category,
    BigDecimal ratingMin,
    BigDecimal ratingMax,
    String legalName,
    String shortName,
    String taxId,
    String okedCode,
    String vatRegistrationCode,
    String registrationNumber,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate registrationDateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate registrationDateTo,
    String legalAddress,
    String actualAddress,
    String phone,
    String email,
    String directorName,
    String chiefAccountantName,
    String bankName,
    String bankMfo,
    String bankAccount,
    String bankAddress,
    String blockedReason
) implements SearchCriteria {
    public static SupplierSearchRequest empty() {
        return new SupplierSearchRequest(
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
            null
        );
    }
}
