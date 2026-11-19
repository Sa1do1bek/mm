package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record ReferenceMappingSearchRequest(
    String search,
    List<String> status,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    boolean includeDeleted,
    UUID referenceId,
    String referenceEntityType,
    String externalSystem,
    String accountingArea
) implements SearchCriteria {
    public static ReferenceMappingSearchRequest empty() {
        return new ReferenceMappingSearchRequest(
            null,
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
