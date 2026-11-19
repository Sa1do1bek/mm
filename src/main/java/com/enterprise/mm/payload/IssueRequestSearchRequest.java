package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record IssueRequestSearchRequest(
    String search,
    List<String> status,
    UUID warehouseId,
    UUID requesterUserId,
    String costObjectType,
    UUID costObjectId,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    boolean includeDeleted
) implements SearchCriteria {
    public static IssueRequestSearchRequest empty() {
        return new IssueRequestSearchRequest(null, null, null, null, null, null, null, null, false);
    }
}
