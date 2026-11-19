package com.enterprise.mm.payload;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record ServicePartialAcceptRequest(
    @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal acceptedVolume,
    @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal rejectedVolume,
    String remarks
) {
}
