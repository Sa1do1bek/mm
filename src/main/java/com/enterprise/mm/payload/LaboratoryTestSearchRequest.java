package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record LaboratoryTestSearchRequest(
    String search,
    List<String> status,
    List<UUID> materialIds,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    boolean includeDeleted
) implements SearchCriteria {
    public static LaboratoryTestSearchRequest empty() {
        return new LaboratoryTestSearchRequest(
            null,
            null,
            null,
            null,
            null,
            false
        );
    }
}
