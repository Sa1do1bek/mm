package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record FixedAssetRevaluationRequest(
    Long expectedVersion,
    @NotNull BigDecimal newAcquisitionValue,
    Integer newUsefulLifeMonths,
    BigDecimal newSalvageValue,
    LocalDate effectiveDate,
    String reason,
    List<String> evidenceIds
) {
}
