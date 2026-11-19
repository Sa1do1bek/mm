package com.enterprise.mm.payload;

import com.enterprise.mm.enums.ProcurementFrameworkLedgerType;
import com.enterprise.mm.enums.ProcurementFrameworkStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class ProcurementFrameworkDtos {
    private ProcurementFrameworkDtos() {}

    public record CreateRequest(
        UUID procedureId,
        UUID methodDecisionId,
        UUID purchaseRequestId,
        @NotNull UUID supplierId,
        List<UUID> supplierIds,
        String supplierModel,
        String allocationBasis,
        @NotNull LocalDate validFrom,
        LocalDate validTo,
        @NotNull @Pattern(regexp = "[A-Za-z]{3}") String currencyCode,
        @NotNull @Positive BigDecimal ceilingAmount,
        String callOffPolicy,
        String awardReference,
        String contractNumber
    ) {}

    public record CallOffRequest(
        @NotNull @Positive BigDecimal amount,
        @NotNull @Pattern(regexp = "[A-Za-z]{3}") String currencyCode,
        String idempotencyKey,
        String callOffReference,
        UUID supplierId,
        UUID purchaseRequestId,
        UUID procedureId,
        UUID purchaseOrderId,
        LocalDate orderDate,
        LocalDate expectedDeliveryDate,
        String allocationBasis
    ) {}

    public record ReversalRequest(
        String idempotencyKey,
        String reversalReference
    ) {}

    public record Response(
        UUID id,
        UUID procedureId,
        UUID methodDecisionId,
        UUID purchaseRequestId,
        UUID contractId,
        String awardReference,
        UUID supplierId,
        List<UUID> supplierIds,
        String supplierModel,
        String allocationBasis,
        LocalDate validFrom,
        LocalDate validTo,
        String currencyCode,
        BigDecimal ceilingAmount,
        BigDecimal committedAmount,
        BigDecimal consumedAmount,
        BigDecimal remainingAmount,
        String callOffPolicy,
        ProcurementFrameworkStatus status,
        String approvalReference,
        LocalDateTime approvedAt,
        String approvedBy,
        LocalDateTime createdAt
    ) {}

    public record LedgerResponse(
        UUID id,
        UUID frameworkId,
        ProcurementFrameworkLedgerType transactionType,
        String callOffReference,
        UUID purchaseOrderId,
        UUID sourcePurchaseRequestId,
        UUID sourceProcedureId,
        BigDecimal amount,
        String currencyCode,
        String idempotencyKey,
        UUID reversalReference,
        LocalDateTime occurredAt
    ) {}

    public record Readiness(
        UUID frameworkId,
        boolean ready,
        ProcurementFrameworkStatus status,
        BigDecimal remainingAmount,
        String blockingReason
    ) {}
}
