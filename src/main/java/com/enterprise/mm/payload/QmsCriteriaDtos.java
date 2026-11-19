package com.enterprise.mm.payload;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public final class QmsCriteriaDtos {
    private QmsCriteriaDtos() {
    }

    public record CriteriaCreateRequest(
        @NotNull UUID companyId,
        @Size(max = 80) String code,
        @NotBlank @Size(max = 240) String name,
        String description,
        @NotNull @DecimalMin(value = "1.00") BigDecimal maxScore,
        @NotNull @DecimalMin(value = "1.000") @DecimalMax(value = "10.000") BigDecimal weight,
        Boolean isActive
    ) {
    }

    public record CriteriaUpdateRequest(
        @Size(max = 80) String code,
        @NotBlank @Size(max = 240) String name,
        String description,
        @NotNull @DecimalMin(value = "1.00") BigDecimal maxScore,
        @NotNull @DecimalMin(value = "1.000") @DecimalMax(value = "10.000") BigDecimal weight,
        Boolean isActive
    ) {
    }

    public record CriteriaResponse(
        UUID id,
        String tenantId,
        UUID companyId,
        String code,
        String name,
        String status,
        String description,
        BigDecimal maxScore,
        BigDecimal weight,
        Boolean isActive,
        boolean deleted,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long version
    ) {
    }
}
