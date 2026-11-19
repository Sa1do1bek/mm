package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class IntegrationHealthCommandDtos {
    private IntegrationHealthCommandDtos() {
    }

    public record OperatorCommand(
        @NotNull Long expectedVersion,
        @NotBlank @Size(min = 3, max = 1000) String reason
    ) {
    }
}
