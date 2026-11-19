package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MaterialGovernanceActionRequest(
    @NotNull Long expectedVersion,
    @NotBlank @Size(max = 1000) String comment
) {
}
