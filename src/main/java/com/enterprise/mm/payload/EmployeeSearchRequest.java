package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;

public record EmployeeSearchRequest(
    String search,
    List<String> status,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    boolean includeDeleted,
    String employmentStatus
) implements SearchCriteria {
    public static EmployeeSearchRequest empty() {
        return new EmployeeSearchRequest(
            null,
            null,
            null,
            null,
            false,
            null
        );
    }
}
