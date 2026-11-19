package com.enterprise.mm.payload;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

public final class NonMovingStockCaseCommandDtos {
    private NonMovingStockCaseCommandDtos() {
    }

    public record AssignmentRequest(
        @NotNull UUID responsibleUserId,
        @NotNull @Future LocalDate reviewDueDate,
        long expectedVersion
    ) {
    }

    public record DueDateRequest(
        @NotNull @Future LocalDate reviewDueDate,
        @NotBlank String reason,
        long expectedVersion
    ) {
    }

    public record TransitionRequest(String comment, long expectedVersion) {
    }

    public record DeferredReviewRequest(
        @NotNull @Future LocalDate nextReviewDate,
        @NotBlank String reason,
        long expectedVersion
    ) {
    }

    public record CloseRequest(@NotBlank String comment, long expectedVersion) {
    }

    public record CancelRequest(@NotBlank String reason, long expectedVersion) {
    }

    public record ReopenRequest(@NotBlank String reason, long expectedVersion) {
    }

    public record ExecutionRequest(@NotBlank String idempotencyKey, long expectedVersion) {
    }
}
