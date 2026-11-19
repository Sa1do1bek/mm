package com.enterprise.mm.payload;

import com.enterprise.mm.enums.ProcurementCloseoutDimension;
import com.enterprise.mm.enums.ProcurementCloseoutLiabilityDisposition;
import com.enterprise.mm.enums.ProcurementCloseoutReadinessStatus;
import com.enterprise.mm.enums.ProcurementCloseoutStatus;
import com.enterprise.mm.enums.ProcurementCloseoutType;
import com.enterprise.mm.enums.ProcurementFinancialClosePolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class ProcurementCloseoutDtos {
    private ProcurementCloseoutDtos() {}

    public record RouteHint(String moduleKey, UUID recordId, String path) {}

    public record Action(
        String key,
        String label,
        boolean enabled,
        String disabledReasonCode,
        String disabledReason,
        String targetModuleKey,
        UUID targetId,
        boolean requiresReason,
        boolean requiresApproval
    ) {}

    public record Blocker(
        UUID id,
        ProcurementCloseoutDimension dimension,
        String blockerCode,
        String severity,
        String entityType,
        UUID entityId,
        UUID entityLineId,
        BigDecimal quantity,
        BigDecimal amount,
        String currency,
        String message,
        String recommendedAction,
        RouteHint route,
        boolean resolved,
        LocalDateTime resolvedAt
    ) {}

    public record Dimension(
        ProcurementCloseoutDimension key,
        ProcurementCloseoutReadinessStatus status,
        BigDecimal remainingQuantity,
        BigDecimal remainingAmount,
        String currency,
        List<Blocker> blockers,
        List<Blocker> warnings,
        List<Action> recommendedActions
    ) {}

    public record Check(
        String code,
        boolean satisfied,
        String observedValue
    ) {}

    public record Readiness(
        UUID procedureId,
        UUID purchaseRequestId,
        LocalDateTime calculatedAt,
        boolean readyForNormalClose,
        boolean readyForEarlyClose,
        boolean sourcingCompleted,
        boolean commercialOutputsCompleted,
        boolean fulfillmentCompleted,
        boolean exceptionsResolved,
        boolean financiallySettled,
        boolean budgetSettled,
        boolean documentsComplete,
        ProcurementFinancialClosePolicy financialClosePolicy,
        ProcurementCloseoutStatus closeoutStatus,
        List<Blocker> blockers,
        List<Blocker> warnings,
        List<Dimension> dimensions,
        List<Action> availableActions,
        List<Check> checks
    ) {
        public boolean ready() {
            return readyForNormalClose;
        }
    }

    public record SummarySupplier(UUID id, String name, String code) {}
    public record SummaryDocument(UUID id, String code, String status) {}

    public record Summary(
        UUID procedureId,
        String procedureReference,
        UUID purchaseRequestId,
        String purchaseRequestReference,
        String procurementMethod,
        List<SummarySupplier> suppliers,
        BigDecimal awardAmount,
        BigDecimal orderedAmount,
        BigDecimal acceptedAmount,
        BigDecimal invoicedAmount,
        BigDecimal paidAmount,
        String currency,
        List<SummaryDocument> contracts,
        List<SummaryDocument> purchaseOrders,
        String responsibleSpecialist,
        ProcurementCloseoutStatus closeoutStatus,
        ProcurementFinancialClosePolicy financialClosePolicy
    ) {}

    public record Response(
        UUID id,
        UUID companyId,
        UUID procedureId,
        UUID purchaseRequestId,
        ProcurementCloseoutStatus status,
        ProcurementCloseoutType closeoutType,
        ProcurementFinancialClosePolicy financialClosePolicy,
        UUID approvalInstanceId,
        UUID approvalTaskId,
        String closeReason,
        String earlyCloseReason,
        String cancellationReason,
        String reopenReason,
        ProcurementCloseoutLiabilityDisposition liabilityDisposition,
        BigDecimal expectedLiabilityAmount,
        BigDecimal unresolvedLiabilityAmount,
        Readiness readinessSnapshot,
        LocalDateTime initiatedAt,
        LocalDateTime submittedAt,
        LocalDateTime approvedAt,
        LocalDateTime closedAt,
        LocalDateTime reopenedAt,
        Long version,
        List<Action> availableActions
    ) {}

    public record CreateRequest(
        ProcurementCloseoutType closeoutType,
        String closeReason,
        String earlyCloseReason,
        ProcurementCloseoutLiabilityDisposition liabilityDisposition,
        @PositiveOrZero BigDecimal expectedLiabilityAmount,
        @PositiveOrZero BigDecimal unresolvedLiabilityAmount,
        String idempotencyKey
    ) {}

    public record CommandRequest(
        Long version,
        String reason,
        ProcurementCloseoutLiabilityDisposition liabilityDisposition,
        List<String> requestedDimensions,
        String idempotencyKey
    ) {}

    public record CancelPoRemainderRequest(
        @NotBlank String reason,
        UUID lineId,
        @PositiveOrZero BigDecimal quantity,
        String businessVersion,
        String idempotencyKey
    ) {}

    public record ResolveRemainingObligationRequest(
        @NotBlank String reason,
        @NotNull ProcurementCloseoutLiabilityDisposition disposition,
        @PositiveOrZero BigDecimal amount,
        String currency,
        String businessVersion,
        String idempotencyKey
    ) {}

    public record HistoricalRow(
        UUID procedureId,
        String procedureReference,
        String purchaseRequestReference,
        String method,
        String supplier,
        String classification,
        String blockerCategory,
        BigDecimal outstandingLiability,
        String currency,
        String recommendedAction
    ) {}

    public record HistoricalReport(
        List<HistoricalRow> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean dryRun,
        LocalDateTime generatedAt
    ) {}
}
