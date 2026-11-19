package com.enterprise.mm.payload;

import com.enterprise.mm.enums.AssetCategory;
import com.enterprise.mm.enums.DepreciationMethod;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record FixedAssetDepreciationConfigRequest(
    Long expectedVersion,
    AssetCategory category,
    DepreciationMethod depreciationMethod,
    Integer usefulLifeMonths,
    BigDecimal salvageValue,
    LocalDate depreciationStartDate,
    String reason
) {
}
