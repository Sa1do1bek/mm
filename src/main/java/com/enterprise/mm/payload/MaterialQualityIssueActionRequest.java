package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MaterialQualityIssueActionRequest(
    @NotNull Long expectedVersion,
    @NotBlank @Size(max = 1000) String comment,
    @Size(max = 120) String assignedTo,
    @Size(max = 500) String evidenceReference
) {
}
