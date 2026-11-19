package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record SupplierCorrectiveActionSearchRequest(
    String search,
    List<String> status,
    UUID supplierId,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    boolean includeDeleted,
    String sourceType,
    UUID sourceId,
    String issueTitle,
    UUID ownerEmployeeId,
    String supplierResponsibleName,
    String severity,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDateTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate submittedFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate submittedTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate verifiedFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate verifiedTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate closedFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate closedTo,
    String verificationResult,
    Boolean recurrencePrevented
) implements SearchCriteria {
    public static SupplierCorrectiveActionSearchRequest empty() {
        return new SupplierCorrectiveActionSearchRequest(
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
