package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public final class MaterialReservationCommandDtos {
    private MaterialReservationCommandDtos() {
    }

    public record ConsumeRequest(
        @NotNull @Positive BigDecimal quantity,
        @NotBlank String reason,
        @NotNull Long expectedVersion,
        String evidenceReference
    ) {
    }

    public record CancelRequest(
        @NotBlank String reason,
        @NotNull Long expectedVersion,
        String evidenceReference
    ) {
    }

    public record ExpireRequest(
        @NotBlank String reason,
        @NotNull Long expectedVersion,
        String evidenceReference
    ) {
    }
}
