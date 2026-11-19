package com.enterprise.mm.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class MarketPriceResearchDtos {
    private MarketPriceResearchDtos() {
    }

    public record MarketPriceResearchJobRequest(
        UUID materialId,
        String currencyCode,
        String quantity,
        String materialName,
        String manufacturer,
        String article,
        String unitOfMeasureCode,
        Boolean includeLocal,
        Boolean includeGlobal,
        Integer maxOffers
    ) {
    }

    public record MarketPriceResearchAiRequest(
        UUID materialId,
        String currencyCode,
        String quantity,
        String materialName,
        String manufacturer,
        String article,
        String unitOfMeasureCode,
        Boolean includeLocal,
        Boolean includeGlobal,
        Integer maxOffers
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record MarketPriceResearchResult(
        String status,
        String confidence,
        @JsonAlias("price_basis")
        String priceBasis,
        @JsonAlias("unit_confidence")
        String unitConfidence,
        MarketMaterial material,
        List<MarketMaterialCandidate> candidates,
        @JsonAlias({"recommended_unit_price", "suggestedUnitPrice", "suggested_unit_price"})
        BigDecimal recommendedUnitPrice,
        @JsonAlias("recommended_price_reliability")
        String recommendedPriceReliability,
        @JsonAlias("recommended_price_method")
        String recommendedPriceMethod,
        @JsonAlias("recommended_total_price")
        BigDecimal recommendedTotalPrice,
        BigDecimal quantity,
        @JsonAlias("unit_of_measure_code")
        String unitOfMeasureCode,
        @JsonAlias("suggested_unit_price")
        BigDecimal suggestedUnitPrice,
        @JsonAlias("currency_code")
        String currencyCode,
        MarketStatistics market,
        @JsonAlias("offer_summary")
        MarketOfferSummary offerSummary,
        @JsonAlias("generated_at")
        String generatedAt,
        List<MarketComparableOffer> offers,
        List<MarketRejectedOffer> rejectedOffers,
        List<MarketSearchQuery> queries,
        List<MarketPriceFinding> findings,
        @JsonAlias("internal_comparison")
        MarketPriceInternalComparison internalComparison,
        String explanation,
        @JsonAlias("llm_used")
        Boolean llmUsed,
        @JsonAlias("market_price_draft")
        MarketPriceDraft marketPriceDraft
    ) {
        public MarketPriceResearchResult(
            String status,
            BigDecimal recommendedUnitPrice,
            String currencyCode,
            String priceBasis,
            String confidence,
            MarketStatistics market,
            List<MarketComparableOffer> offers,
            List<MarketRejectedOffer> rejectedOffers,
            MarketPriceInternalComparison internalComparison,
            List<MarketPriceFinding> findings
        ) {
            this(
                status,
                confidence,
                priceBasis,
                null,
                null,
                List.of(),
                recommendedUnitPrice,
                null,
                null,
                null,
                null,
                null,
                null,
                currencyCode,
                market,
                null,
                null,
                offers,
                rejectedOffers,
                List.of(),
                findings,
                internalComparison,
                null,
                null,
                null
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MarketMaterial(
        @JsonAlias("material_id")
        UUID materialId,
        String code,
        String name,
        String description,
        @JsonAlias("material_group")
        String materialGroup,
        @JsonAlias("unit_of_measure_code")
        String unitOfMeasureCode,
        String manufacturer,
        String article,
        String source
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MarketMaterialCandidate(
        @JsonAlias("material_id")
        UUID materialId,
        String code,
        String name
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MarketStatistics(
        BigDecimal median,
        BigDecimal average,
        BigDecimal min,
        BigDecimal max,
        @JsonAlias("sample_size")
        Integer sampleSize,
        @JsonAlias("outliers_removed")
        Integer outliersRemoved,
        @JsonAlias("spread_ratio")
        BigDecimal spreadRatio
    ) {
        public MarketStatistics(
            BigDecimal median,
            BigDecimal average,
            BigDecimal min,
            BigDecimal max,
            Integer sampleSize
        ) {
            this(median, average, min, max, sampleSize, null, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MarketOfferSummary(
        Integer found,
        @JsonAlias("used_in_statistics")
        Integer usedInStatistics,
        Integer excluded,
        @JsonAlias("local_offers")
        Integer localOffers,
        @JsonAlias("global_offers")
        Integer globalOffers,
        Integer shown
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record MarketComparableOffer(
        String title,
        String source,
        @JsonAlias({"source_url", "url", "link", "sourceLink", "source_link"})
        String sourceUrl,
        @JsonAlias("raw_price")
        String rawPrice,
        @JsonAlias({"currency_code", "currency", "normalizedCurrencyCode", "normalized_currency_code", "convertedCurrencyCode", "converted_currency_code"})
        String currencyCode,
        @JsonAlias({
            "unit_price",
            "unitPriceUzs",
            "unit_price_uzs",
            "priceUzs",
            "price_uzs",
            "priceValue",
            "price_value",
            "amount",
            "value",
            "price"
        })
        BigDecimal unitPrice,
        @JsonAlias({"converted_unit_price", "convertedPrice", "converted_price", "normalizedUnitPrice", "normalized_unit_price", "normalizedPrice", "normalized_price", "normalizedAmount", "normalized_amount"})
        BigDecimal convertedUnitPrice,
        @JsonAlias({"fx_rate", "exchangeRate", "exchange_rate"})
        BigDecimal fxRate,
        @JsonAlias({"fx_source", "fxRateSource", "fx_rate_source", "exchangeRateSource", "exchange_rate_source"})
        String fxSource,
        @JsonAlias("unit_of_measure_code")
        String unitOfMeasureCode,
        @JsonAlias("unit_match")
        String unitMatch,
        @JsonAlias("observed_at")
        String observedAt,
        @JsonAlias("source_kind")
        String sourceKind,
        @JsonAlias({"score", "relevanceScore", "relevance_score", "confidenceScore", "confidence_score"})
        BigDecimal relevance,
        String basis,
        @JsonAlias({"extractionType", "extraction_type"})
        String extraction,
        Boolean matched,
        @JsonAlias("used_in_statistics")
        Boolean usedInStatistics,
        Boolean excluded,
        @JsonAlias({"exclude_reason", "exclusionReason", "exclusion_reason", "reason"})
        String excludeReason
    ) {
        public MarketComparableOffer(
            String title,
            String source,
            String sourceUrl,
            BigDecimal unitPrice,
            String currencyCode,
            BigDecimal relevance
        ) {
            this(
                title,
                source,
                sourceUrl,
                null,
                currencyCode,
                unitPrice,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                relevance,
                null,
                null,
                null,
                null,
                null,
                null
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MarketRejectedOffer(
        String title,
        String source,
        @JsonAlias({"source_url", "url", "link", "sourceLink", "source_link"})
        String sourceUrl,
        @JsonAlias({
            "unit_price",
            "convertedUnitPrice",
            "converted_unit_price",
            "convertedPrice",
            "converted_price",
            "normalizedUnitPrice",
            "normalized_unit_price",
            "normalizedPrice",
            "normalized_price",
            "normalizedAmount",
            "normalized_amount",
            "unitPriceUzs",
            "unit_price_uzs",
            "priceUzs",
            "price_uzs",
            "priceValue",
            "price_value",
            "amount",
            "value",
            "price"
        })
        BigDecimal unitPrice,
        @JsonAlias({"currency_code", "currency", "normalizedCurrencyCode", "normalized_currency_code", "convertedCurrencyCode", "converted_currency_code"})
        String currencyCode,
        @JsonAlias({"score", "relevanceScore", "relevance_score", "confidenceScore", "confidence_score"})
        BigDecimal relevance,
        Boolean excluded,
        @JsonAlias({"excludeReason", "exclude_reason", "exclusion_reason", "reason", "rejectionReason", "rejection_reason"})
        String exclusionReason
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MarketPriceFinding(
        String severity,
        String category,
        String message,
        String details
    ) {
        public MarketPriceFinding(String severity, String category, String message) {
            this(severity, category, message, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MarketSearchQuery(
        String engine,
        String query,
        String basis,
        @JsonAlias("result_count")
        Integer resultCount,
        Boolean cached,
        String error
    ) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static MarketSearchQuery fromText(String query) {
            return new MarketSearchQuery(null, query, null, null, null, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MarketPriceInternalComparison(
        String verdict,
        @JsonAlias("sample_size")
        Integer sampleSize,
        @JsonAlias("average_historical_price")
        BigDecimal averageHistoricalPrice,
        @JsonAlias("last_purchase_price")
        BigDecimal lastPurchasePrice,
        @JsonAlias("currency_code")
        String currencyCode,
        @JsonAlias("market_vs_internal_percent")
        BigDecimal marketVsInternalPercent
    ) {
        public MarketPriceInternalComparison(
            Integer sampleSize,
            BigDecimal averageHistoricalPrice,
            BigDecimal lastPurchasePrice,
            BigDecimal marketVsInternalPercent
        ) {
            this(null, sampleSize, averageHistoricalPrice, lastPurchasePrice, null, marketVsInternalPercent);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MarketPriceDraft(
        @JsonAlias("material_id")
        UUID materialId,
        @JsonAlias("market_unit_price")
        BigDecimal marketUnitPrice,
        @JsonAlias("currency_code")
        String currencyCode,
        @JsonAlias("effective_date")
        String effectiveDate,
        @JsonAlias("source_type")
        String sourceType,
        @JsonAlias("source_reference")
        String sourceReference
    ) {
    }

}
