package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.enums.ValuationMethod;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;

public record MaterialSearchRequest(
    String search,
    List<String> status,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    boolean includeDeleted,
    java.util.UUID id,
    String code,
    String name,
    String description,
    Boolean deleted,
    String createdBy,
    String updatedBy,
    Long version,
    java.util.UUID materialGroupId,
    java.util.UUID unitOfMeasureId,
    ValuationMethod valuationMethod,
    java.math.BigDecimal minStock,
    java.math.BigDecimal safetyStock,
    java.math.BigDecimal reorderPoint,
    String barcode,
    String qrCode,
    String rfidTag,
    String mxikCode,
    java.util.UUID tnvedCodeId,
    String tnvedCode,
    String article,
    String manufacturer,
    String countryOfOrigin,
    Integer shelfLifeDays,
    String hazardClass,
    java.math.BigDecimal maxStock,
    java.util.UUID replacementMaterialId,
    String lifecycleReason,
    @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime lifecycleChangedAt,
    // Internal business classification: SERVICES, FIXED_ASSET or TMB.
    String materialType,
    // Product-code kind derived from the linked MXIK classifier: GOODS (products page) or
    // SERVICE (services page). Null means no type restriction.
    String type,
    List<String> stockStatus,
    String materialSubtype
) implements SearchCriteria {
    public static MaterialSearchRequest empty() {
        return new MaterialSearchRequest(
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
