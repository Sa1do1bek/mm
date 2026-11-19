package com.enterprise.mm.payload;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record FeedbackViewport(
    @Min(1) @Max(10000) int width,
    @Min(1) @Max(10000) int height,
    @NotNull @DecimalMin("0.1") BigDecimal devicePixelRatio
) {
}
