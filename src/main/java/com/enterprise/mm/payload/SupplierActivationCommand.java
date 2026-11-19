package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SupplierActivationCommand(
    @NotNull Long expectedVersion,
    @NotBlank @Size(min = 3, max = 2000) String comment
) {
}
