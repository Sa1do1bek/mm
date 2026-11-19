package com.enterprise.mm.service;

import com.enterprise.mm.enums.ManagementOpportunityConfidence;
import com.enterprise.mm.enums.ManagementOpportunityDataCompleteness;
import com.enterprise.mm.enums.ManagementOpportunityType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ManagementOpportunityCalculatedCandidate(
    ManagementOpportunityType type,
    UUID policyId,
    String sourceAggregateType,
    UUID sourceAggregateId,
    UUID sourceLineId,
    UUID materialId,
    UUID supplierId,
    UUID warehouseId,
    LocalDate asOfDate,
    String sourceCurrencyCode,
    String normalizedCurrencyCode,
    BigDecimal sourceAmount,
    BigDecimal normalizedAmount,
    BigDecimal baselineAmount,
    BigDecimal actualAmount,
    BigDecimal quantity,
    BigDecimal exchangeRate,
    UUID exchangeRateId,
    LocalDate exchangeRateDate,
    String exchangeRateSource,
    String normalizationQuality,
    String formulaCode,
    String formulaSnapshot,
    ManagementOpportunityConfidence confidence,
    ManagementOpportunityDataCompleteness completeness,
    String skipReason,
    List<ManagementOpportunityEvidenceDescriptor> evidence
) {
    public boolean completePositiveNormalized() {
        return completeness == ManagementOpportunityDataCompleteness.COMPLETE
            && normalizedAmount != null
            && normalizedAmount.signum() > 0;
    }

    public boolean persistsAsCase() {
        return completePositiveNormalized()
            || completeness == ManagementOpportunityDataCompleteness.DATA_INCOMPLETE;
    }
}
