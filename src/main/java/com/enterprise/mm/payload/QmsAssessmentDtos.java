package com.enterprise.mm.payload;

import com.enterprise.mm.enums.QmsAssessmentStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class QmsAssessmentDtos {
    private QmsAssessmentDtos() {
    }

    public record CreateDraftAssessmentRequest(
        @NotNull UUID companyId,
        @NotNull UUID supplierId,
        @NotNull LocalDate assessmentDate,
        UUID assessedBy,
        @Size(max = 80) String code,
        @Size(max = 240) String name,
        String description
    ) {
    }

    public record UpdateAssessmentRequest(
        @NotNull LocalDate assessmentDate,
        UUID assessedBy,
        @Size(max = 80) String code,
        @Size(max = 240) String name,
        String description
    ) {
    }

    public record AddDetailRequest(
        @NotNull UUID criteriaId,
        @NotNull @DecimalMin("0.00") BigDecimal givenScore,
        String comment
    ) {
    }

    public record SupplierAssessmentResponse(
        UUID id,
        String tenantId,
        UUID companyId,
        UUID supplierId,
        String code,
        String name,
        String description,
        LocalDate assessmentDate,
        BigDecimal totalWeightedScore,
        QmsAssessmentStatus status,
        UUID assessedBy,
        boolean deleted,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long version,
        List<SupplierAssessmentDetailResponse> details
    ) {
    }

    public record SupplierAssessmentDetailResponse(
        UUID id,
        UUID criteriaId,
        String criteriaCode,
        String criteriaName,
        BigDecimal maxScore,
        BigDecimal weight,
        BigDecimal givenScore,
        String comment
    ) {
    }
}
