package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record SupplierEvaluationSearchRequest(
    String search,
    List<String> status,
    UUID supplierId,
    UUID purchaseOrderId,
    List<UUID> materialIds,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    boolean includeDeleted,
    BigDecimal totalScoreMin,
    BigDecimal totalScoreMax,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate evaluatedFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate evaluatedTo
) implements SearchCriteria {
    public static SupplierEvaluationSearchRequest empty() {
        return new SupplierEvaluationSearchRequest(null, null, null, null, null, null, null, false, null, null, null, null);
    }
}
