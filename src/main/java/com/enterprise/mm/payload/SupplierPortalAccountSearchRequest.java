package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record SupplierPortalAccountSearchRequest(
    String search,
    List<String> status,
    UUID supplierId,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    boolean includeDeleted,
    Boolean mfaRequired,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate lastLoginFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate lastLoginTo,
    String loginEmail,
    String contactPerson,
    String phone,
    String allowedIpRange,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate invitedFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate invitedTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate activatedFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate activatedTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate lastSubmissionFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate lastSubmissionTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate termsAcceptedFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate termsAcceptedTo
) implements SearchCriteria {
    public static SupplierPortalAccountSearchRequest empty() {
        return new SupplierPortalAccountSearchRequest(
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
            null
        );
    }
}
