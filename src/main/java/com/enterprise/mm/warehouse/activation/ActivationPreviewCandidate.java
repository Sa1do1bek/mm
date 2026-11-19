package com.enterprise.mm.warehouse.activation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * An immutable representation of a single evaluated 1C source balance position
 * showing its current classification, represented native amounts, and proposed deltas.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivationPreviewCandidate(
    String sourceKey,
    String accountCode,
    String accountName,
    UUID materialId,
    String materialCode,
    String materialName,
    UUID warehouseId,
    String warehouseCode,
    String warehouseName,
    String stockCategory,
    String uom,
    BigDecimal sourceQuantity,
    BigDecimal sourceAmount,
    BigDecimal representedQuantity,
    BigDecimal representedAmount,
    BigDecimal deltaQuantity,
    BigDecimal deltaAmount,
    SourcePositionClassification classification,
    String sourceRowHash,
    String blockedReason
) {
    @JsonProperty(value = "eligible", access = JsonProperty.Access.READ_ONLY)
    public boolean isEligible() {
        return classification == SourcePositionClassification.ELIGIBLE_DELTA;
    }

    @JsonProperty(value = "warnings", access = JsonProperty.Access.READ_ONLY)
    public List<String> getWarnings() {
        if (blockedReason != null && !blockedReason.isBlank()) {
            return List.of(blockedReason);
        }
        return Collections.emptyList();
    }

    @JsonProperty(value = "quantity", access = JsonProperty.Access.READ_ONLY)
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    public BigDecimal getQuantity() {
        if (classification == SourcePositionClassification.ELIGIBLE_DELTA) {
            return deltaQuantity != null ? deltaQuantity : (sourceQuantity != null ? sourceQuantity : BigDecimal.ZERO);
        }
        return sourceQuantity != null ? sourceQuantity : (deltaQuantity != null ? deltaQuantity : BigDecimal.ZERO);
    }

    @JsonProperty(value = "totalValue", access = JsonProperty.Access.READ_ONLY)
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    public BigDecimal getTotalValue() {
        if (classification == SourcePositionClassification.ELIGIBLE_DELTA) {
            return deltaAmount != null ? deltaAmount : (sourceAmount != null ? sourceAmount : BigDecimal.ZERO);
        }
        return sourceAmount != null ? sourceAmount : (deltaAmount != null ? deltaAmount : BigDecimal.ZERO);
    }

    @JsonProperty(value = "unitCost", access = JsonProperty.Access.READ_ONLY)
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    public BigDecimal getUnitCost() {
        BigDecimal q = getQuantity();
        BigDecimal v = getTotalValue();
        if (q != null && q.signum() != 0 && v != null) {
            return v.divide(q, 4, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    }
}
