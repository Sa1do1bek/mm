package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.enums.WorkInspectionResult;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record WorkCompletionActSearchRequest(
    String search,
    List<String> status,
    UUID purchaseOrderId,
    UUID purchaseOrderItemId,
    UUID contractId,
    String milestone,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStartFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStartTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEndFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEndTo,
    WorkInspectionResult inspectionResult,
    boolean includeDeleted
) implements SearchCriteria {
    public static WorkCompletionActSearchRequest empty() {
        return new WorkCompletionActSearchRequest(
            null, null, null, null, null, null, null, null, null, null, null, null, null, false
        );
    }
}
