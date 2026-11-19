package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record ProcurementProcedureSearchRequest(
    String search,
    List<String> status,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    boolean includeDeleted,
    String procedureNumber,
    UUID methodDecisionId,
    UUID procurementPlanId,
    UUID purchaseRequestId,
    List<String> method,
    UUID campaignId,
    UUID rfqId
) implements SearchCriteria {
}
