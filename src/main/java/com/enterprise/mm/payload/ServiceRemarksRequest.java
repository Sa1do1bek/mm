package com.enterprise.mm.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ServiceRemarksRequest(@Valid @NotEmpty List<Remark> remarks) {
    public record Remark(
        @NotBlank String severity,
        @NotBlank String description,
        LocalDate dueDate,
        UUID assignedTo
    ) {
    }
}
