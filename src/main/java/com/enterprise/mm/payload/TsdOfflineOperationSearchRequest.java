package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;

public record TsdOfflineOperationSearchRequest(
    String search,
    List<String> status,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    boolean includeDeleted
) implements SearchCriteria {
    public static TsdOfflineOperationSearchRequest empty() {
        return new TsdOfflineOperationSearchRequest(
            null,
            null,
            null,
            null,
            false
        );
    }
}
