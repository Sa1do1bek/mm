package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record SupplierRelatedPartySearchRequest(
    String search,
    List<String> status,
    UUID supplierId,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    boolean includeDeleted,
    String partyType,
    String riskLevel,
    String fullName,
    String legalName,
    String taxId,
    String countryCode,
    String positionTitle,
    String phone,
    String email,
    Boolean pepFlag,
    Boolean sanctionsMatch,
    Boolean conflictOfInterestFlag,
    BigDecimal ownershipMin,
    BigDecimal ownershipMax,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validTo
) implements SearchCriteria {
    public static SupplierRelatedPartySearchRequest empty() {
        return new SupplierRelatedPartySearchRequest(
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
            null,
            null,
            null
        );
    }
}
