package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record QmsCriteriaSearchRequest(
    UUID companyId,
    String search,
    boolean includeInactive,
    boolean includeDeleted,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo
) implements SearchCriteria {
    public static QmsCriteriaSearchRequest empty() {
        return new QmsCriteriaSearchRequest(null, null, false, false, null, null);
    }
}
