package com.enterprise.mm.payload;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ServiceAcceptanceWorkspaceCommandDtos {
    private ServiceAcceptanceWorkspaceCommandDtos() {
    }

    public record PartialAcceptCommand(
        @NotNull Long expectedVersion,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal acceptedVolume,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal rejectedVolume,
        @Size(max = 1000) String remarks
    ) {
    }

    public record MilestoneAcceptCommand(
        @NotNull Long expectedVersion,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal acceptedVolume,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal rejectedVolume,
        boolean finalAcceptance,
        @Size(max = 1000) String remarks
    ) {
    }

    public record IntakeMilestone(
        @NotBlank @Size(max = 240) String name,
        LocalDate dueDate,
        @DecimalMin(value = "0.0", inclusive = false) BigDecimal quantity,
        @DecimalMin(value = "0.0", inclusive = false) BigDecimal amount
    ) {
    }

    public record IntakeCommand(
        @NotBlank @Size(max = 240) String title,
        @Size(max = 120) String category,
        @NotBlank @Size(max = 8000) String scopeOfWork,
        UUID supplierId,
        @NotNull UUID departmentId,
        @NotNull UUID responsibleId,
        LocalDate plannedStartDate,
        @NotNull LocalDate plannedEndDate,
        @NotBlank @Size(max = 20) String acceptanceMode,
        @DecimalMin(value = "0.0", inclusive = false) BigDecimal orderedQuantity,
        @Size(max = 30) String uomCode,
        @DecimalMin(value = "0.0", inclusive = false) BigDecimal orderedAmount,
        @Size(min = 3, max = 3) String currencyCode,
        @NotNull List<IntakeMilestone> milestones,
        List<UUID> evidenceIds,
        List<String> approvalPreview
    ) {
        public IntakeCommand {
            milestones = milestones == null ? List.of() : List.copyOf(milestones);
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            approvalPreview = approvalPreview == null ? List.of() : List.copyOf(approvalPreview);
        }
    }

    public record PaymentCommand(
        @NotNull Long expectedVersion,
        @Size(max = 120) String paymentReference,
        @Size(max = 1000) String comment
    ) {
    }

    public record VersionedActionCommand(
        @NotNull Long expectedVersion,
        @Size(max = 1000) String reason
    ) {
    }

    public record RemarkCommand(
        @NotNull Long expectedVersion,
        @NotBlank @Size(max = 30) String severity,
        @NotBlank @Size(max = 2000) String description,
        @NotNull LocalDate dueDate,
        @NotNull UUID assignedTo
    ) {
    }

    public record ClaimCommand(
        @NotNull Long expectedVersion,
        @NotBlank @Size(max = 60) String claimType,
        @NotBlank @Size(max = 40) String severity,
        @NotBlank @Size(max = 4000) String description,
        @NotNull LocalDate deadline,
        @NotNull UUID assignedTo,
        @DecimalMin(value = "0.0", inclusive = true) BigDecimal penaltyAmount,
        @Size(min = 3, max = 3) String currencyCode
    ) {
    }

    public record ActCreateCommand(
        @NotNull Long expectedVersion,
        @NotBlank @Size(max = 120) String acceptedBy,
        @NotBlank @Size(max = 2000) String commissionNotes,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal amount,
        @NotBlank @Size(max = 20) String stage,
        LocalDate periodStart,
        LocalDate periodEnd
    ) {
    }

    public record ActReviewCommand(
        @NotNull Long expectedVersion,
        @NotBlank @Size(max = 30) String decision,
        @Size(max = 1000) String reason
    ) {
    }

    public record WorkActCreateCommand(
        @NotNull Long expectedVersion,
        @NotBlank @Size(max = 240) String name,
        @Size(max = 240) String workLocation,
        @Size(max = 120) String milestone,
        LocalDate periodStart,
        LocalDate periodEnd,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal completedQuantity,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal amount,
        @NotBlank @Size(max = 40) String inspectionResult,
        @Size(max = 120) String acceptedBy,
        @Size(max = 2000) String commissionNotes,
        @Size(max = 2000) String remarks
    ) {
    }

    public record IntegrationRetryCommand(
        @NotNull Long expectedVersion,
        @NotBlank @Size(min = 3, max = 1000) String reason
    ) {
    }
}
