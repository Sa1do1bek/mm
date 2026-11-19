package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ApprovalFlowSearchRequest(
    String search,
    LocalDate dateFrom,
    LocalDate dateTo,
    boolean includeDeleted,
    String moduleKey,
    UUID departmentId,
    List<String> requestType,
    Boolean active
) implements SearchCriteria {
    public static ApprovalFlowSearchRequest empty() {
        return new ApprovalFlowSearchRequest(
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            null
        );
    }
}
