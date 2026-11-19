package com.enterprise.mm.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record QmsAssessmentCreateWithDetailsRequest(
    @NotNull UUID companyId,
    @NotNull UUID supplierId,
    @NotNull LocalDate assessmentDate,
    UUID assessedBy,
    @Size(max = 80) String code,
    @Size(max = 240) String name,
    String description,
    @NotEmpty @Valid List<@Valid Detail> details
) {
    public record Detail(
        @NotNull UUID criteriaId,
        @NotNull @DecimalMin("0.00") BigDecimal givenScore,
        @Size(max = 2000) String comment
    ) {
    }
}
