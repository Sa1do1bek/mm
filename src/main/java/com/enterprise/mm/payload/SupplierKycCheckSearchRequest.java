package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record SupplierKycCheckSearchRequest(
    String search,
    List<String> status,
    UUID supplierId,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    boolean includeDeleted,
    String checkType,
    String riskLevel,
    Boolean remediationRequired,
    BigDecimal scoreMin,
    BigDecimal scoreMax,
    String provider,
    String externalCheckId,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkedFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkedTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validUntilFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validUntilTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate nextReviewDateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate nextReviewDateTo,
    UUID evidenceAttachmentId,
    String findings
) implements SearchCriteria {
    public static SupplierKycCheckSearchRequest empty() {
        return new SupplierKycCheckSearchRequest(
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
