package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record TenderBidOpeningProtocolSearchRequest(
    String search,
    List<String> status,
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
    UUID rfqId,
    String protocolNumber,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime openedAtFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime openedAtTo,
    String openedBy,
    Integer quorumRequired,
    Integer quorumPresent,
    Boolean quorumConfirmed,
    Integer quotationCount,
    Integer acceptedQuotationCount,
    Integer rejectedQuotationCount,
    String minutes,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime postedAtFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime postedAtTo
) implements SearchCriteria {
    public static TenderBidOpeningProtocolSearchRequest empty() {
        return new TenderBidOpeningProtocolSearchRequest(
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
