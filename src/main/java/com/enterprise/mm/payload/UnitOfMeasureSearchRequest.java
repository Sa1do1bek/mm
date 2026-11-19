package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

public record UnitOfMeasureSearchRequest(
    String search,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    boolean includeDeleted
) implements SearchCriteria {
    public static UnitOfMeasureSearchRequest empty() {
        return new UnitOfMeasureSearchRequest(
            null,
            null,
            null,
            false
        );
    }
}
