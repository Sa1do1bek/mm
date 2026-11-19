package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record SupplierPortalSubmissionSearchRequest(
    String search,
    List<String> status,
    UUID supplierId,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    boolean includeDeleted,
    UUID portalAccountId,
    UUID referenceId,
    String referenceEntityType,
    String submissionType,
    String payloadJson,
    UUID attachmentId,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate submittedFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate submittedTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reviewedFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reviewedTo,
    String reviewedBy,
    String reviewComment
) implements SearchCriteria {
    public static SupplierPortalSubmissionSearchRequest empty() {
        return new SupplierPortalSubmissionSearchRequest(
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
            null
        );
    }
}
