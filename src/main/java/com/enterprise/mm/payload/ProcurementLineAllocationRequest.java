package com.enterprise.mm.payload;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record ProcurementLineAllocationRequest(
    @NotBlank String sourceDocumentType,
    @NotNull UUID sourceDocumentId,
    @NotNull UUID sourceLineId,
    @NotBlank String targetDocumentType,
    @NotNull UUID targetDocumentId,
    @NotNull UUID targetLineId,
    @NotNull @DecimalMin(value = "0.0001") BigDecimal sourceQuantity,
    @NotNull @DecimalMin(value = "0.0001") BigDecimal allocatedQuantity,
    @NotNull @DecimalMin(value = "0.00") BigDecimal allocatedAmount,
    @NotBlank String currencyCode,
    UUID supplierId,
    @NotBlank String idempotencyKey
) {
}
