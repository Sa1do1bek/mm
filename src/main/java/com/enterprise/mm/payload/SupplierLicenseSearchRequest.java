package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record SupplierLicenseSearchRequest(
    String search,
    List<String> status,
    UUID supplierId,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    boolean includeDeleted,
    String licenseType,
    Boolean renewalRequired,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validTo
) implements SearchCriteria {
    public static SupplierLicenseSearchRequest empty() {
        return new SupplierLicenseSearchRequest(null, null, null, null, null, false, null, null, null, null);
    }
}
