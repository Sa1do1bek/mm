package com.enterprise.mm.warehouse.activation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivationPreviewResult(
    long totalCount,
    long eligibleCount,
    long alreadyRepresentedCount,
    long valueOnlyCount,
    long negativeDisputedCount,
    long historicalOnlyCount,
    long unresolvedCount,
    BigDecimal totalSourceQuantity,
    BigDecimal totalSourceAmount,
    BigDecimal totalDeltaQuantity,
    BigDecimal totalDeltaAmount,
    List<ActivationPreviewCandidate> items,
    int page,
    int size
) {
    @JsonProperty(value = "candidates", access = JsonProperty.Access.READ_ONLY)
    public List<ActivationPreviewCandidate> getCandidates() {
        return items;
    }

    @JsonProperty(value = "totalCandidates", access = JsonProperty.Access.READ_ONLY)
    public long getTotalCandidates() {
        return totalCount;
    }

    @JsonProperty(value = "ineligibleCount", access = JsonProperty.Access.READ_ONLY)
    public long getIneligibleCount() {
        return Math.max(0, totalCount - eligibleCount);
    }
}
