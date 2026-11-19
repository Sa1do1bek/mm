package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record ApprovalWorkflowSearchRequest(
    String search,
    List<String> status,
    String moduleKey,
    String requestType,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    boolean includeDeleted,
    UUID referenceId,
    String referenceEntityType
) implements SearchCriteria {
    public static ApprovalWorkflowSearchRequest empty() {
        return new ApprovalWorkflowSearchRequest(
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            null
        );
    }
}
