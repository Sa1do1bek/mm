package com.enterprise.mm.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record TransferDiscrepancyRequest(
    @NotNull Long expectedVersion,
    @Valid @NotEmpty List<Line> lines
) {
    public record Line(
        @NotNull UUID transferLineId,
        @PositiveOrZero BigDecimal damagedQuantity,
        @PositiveOrZero BigDecimal missingQuantity,
        String reason,
        String evidence
    ) {
    }
}
