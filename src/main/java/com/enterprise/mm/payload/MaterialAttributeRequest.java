package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MaterialAttributeRequest(
    @NotBlank
    @Size(max = 255)
    String key,

    @NotBlank
    @Size(max = 4000)
    String value
) {
}
