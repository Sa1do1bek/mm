package com.enterprise.mm.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

public final class RfqComparisonDtos {
    private RfqComparisonDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RfqComparisonResult(
        String summary,
        String recommendation,
        String comparabilityStatus,
        String lowestTotalSupplierName,
        String referenceCurrency,
        String rfqStatus,
        String rfqDueDate,
        List<SupplierComparison> suppliers,
        List<RfqComparisonItem> items,
        List<SupplierAffiliation> affiliations,
        List<RfqFinding> findings
    ) {
        public RfqComparisonResult {
            suppliers = suppliers == null ? List.of() : List.copyOf(suppliers);
            items = items == null ? List.of() : List.copyOf(items);
            affiliations = affiliations == null ? List.of() : List.copyOf(affiliations);
            findings = findings == null ? List.of() : List.copyOf(findings);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SupplierComparison(
        String quotationKey,
        UUID supplierId,
        String supplierName,
        UUID quotationId,
        String quotationCode,
        String quotationStatus,
        String quotationSource,
        String parseMode,
        String totalNet,
        String totalTax,
        String totalGross,
        String normalizedTotal,
        String effectiveTotal,
        String storedTotalAmount,
        String exchangeRateSource,
        Boolean isLowestTotal,
        String incoterms,
        Boolean taxIncluded,
        String validUntil,
        Boolean validityExpired,
        Boolean supplierBlocked,
        String paymentTerms,
        String deliveryTerms,
        Integer maxDeliveryDays,
        Integer warrantyMonths,
        Integer linesQuoted,
        Integer linesNoBid,
        Integer linesMissing,
        List<String> warnings,
        List<String> exceptions,
        SupplierPerformance performance
    ) {
        public SupplierComparison {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            exceptions = exceptions == null ? List.of() : List.copyOf(exceptions);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SupplierPerformance(
        Integer evaluations,
        Integer lateDeliveries,
        String averageLateDays,
        String onTimePercent,
        String rejectedPercent,
        String mmRating,
        String mmAverageScore
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RfqComparisonItem(
        String itemKey,
        UUID rfqItemId,
        String materialName,
        String requestedQuantity,
        List<ItemOffer> offers
    ) {
        public RfqComparisonItem {
            offers = offers == null ? List.of() : List.copyOf(offers);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ItemOffer(
        String quotationKey,
        String unitPrice,
        String offeredQuantity,
        String requestedQuantity,
        String lineNet,
        String lineTax,
        String lineGross,
        String taxRate,
        String status,
        List<String> warnings,
        List<String> exceptions
    ) {
        public ItemOffer {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            exceptions = exceptions == null ? List.of() : List.copyOf(exceptions);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SupplierAffiliation(
        @JsonProperty("type") @JsonAlias("signal")
        String signal,
        Boolean blocking,
        List<String> supplierNames,
        @JsonProperty("details") @JsonAlias("detail") String detail
    ) {
        public SupplierAffiliation {
            supplierNames = supplierNames == null ? List.of() : List.copyOf(supplierNames);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RfqFinding(
        String severity,
        String category,
        String title,
        String details
    ) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static RfqFinding fromText(String text) {
            return new RfqFinding(null, null, text, null);
        }
    }
}
