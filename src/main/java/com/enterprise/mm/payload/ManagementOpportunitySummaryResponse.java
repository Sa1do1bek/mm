package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ManagementOpportunitySummaryResponse(
    BigDecimal approvedNormalizedAmount,
    String approvedCurrencyCode,
    boolean mixedApprovedCurrencies,
    long approvedCaseCount,
    long openReviewCount,
    long dataIncompleteCount,
    long currencyConversionMissingCount,
    Instant generatedAt,
    List<CurrencyAmount> approvedCurrencyBuckets,
    List<CurrencyAmount> detectedSourceCurrencyBuckets,
    List<WaterfallCurrency> waterfallByCurrency,
    List<CountBreakdown> statusBreakdown,
    List<CountBreakdown> completenessBreakdown,
    List<AgingBucket> aging,
    ConversionMetadata conversion,
    List<ManagementOpportunityBreakdownResponse.GroupAmount> approvedByType,
    List<ManagementOpportunityBreakdownResponse.GroupAmount> approvedByConfidence,
    List<ManagementOpportunityBreakdownResponse.EntityAmount> approvedBySupplier,
    List<ManagementOpportunityBreakdownResponse.EntityAmount> approvedByWarehouse,
    List<ManagementOpportunityBreakdownResponse.EntityAmount> approvedByMaterialGroup
) {
    public record CurrencyAmount(String currencyCode, BigDecimal amount, long caseCount) {
    }

    public record WaterfallCurrency(
        String currencyCode,
        BigDecimal detectedAmount,
        BigDecimal normalizedAmount,
        BigDecimal approvedAmount,
        BigDecimal realizedAmount,
        long detectedCaseCount,
        long normalizedCaseCount,
        long approvedCaseCount,
        long realizedCaseCount,
        boolean realizedAvailable
    ) {
    }

    public record CountBreakdown(String group, long caseCount) {
    }

    public record AgingBucket(String bucket, long caseCount) {
    }

    public record ConversionMetadata(
        String aggregationBasis,
        long convertedCaseCount,
        long sameCurrencyCaseCount,
        long missingConversionCount,
        LocalDate earliestRateDate,
        LocalDate latestRateDate,
        List<String> rateSources,
        String note
    ) {
    }
}
