package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;

public record LandedLogisticsEventSearchRequest(
    String search,
    List<String> status,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    boolean includeDeleted,
    String externalSystem
) implements SearchCriteria {
    public static LandedLogisticsEventSearchRequest empty() {
        return new LandedLogisticsEventSearchRequest(
            null,
            null,
            null,
            null,
            false,
            null
        );
    }
}
