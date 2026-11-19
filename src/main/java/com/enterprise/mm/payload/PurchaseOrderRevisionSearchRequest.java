package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record PurchaseOrderRevisionSearchRequest(
    String search,
    List<String> status,
    UUID purchaseOrderId,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    boolean includeDeleted,
    String code,
    String name,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdAtFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdAtTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime updatedAtFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime updatedAtTo,
    String createdBy,
    String updatedBy,
    Integer revisionNumber,
    String reason,
    String diffJson,
    String snapshotJson,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime revisedAtFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime revisedAtTo
) implements SearchCriteria {
    public static PurchaseOrderRevisionSearchRequest empty() {
        return new PurchaseOrderRevisionSearchRequest(
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }
}
