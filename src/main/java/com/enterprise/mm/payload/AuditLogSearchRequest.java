package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;

public record AuditLogSearchRequest(
    String search,
    List<String> status,
    List<String> action,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    boolean includeDeleted
) implements SearchCriteria {
    public static AuditLogSearchRequest empty() {
        return new AuditLogSearchRequest(
            null,
            null,
            null,
            null,
            null,
            false
        );
    }
}
