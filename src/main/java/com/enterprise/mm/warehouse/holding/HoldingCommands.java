package com.enterprise.mm.warehouse.holding;

import com.enterprise.mm.error.BadRequestException;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class HoldingCommands {

    public record DispatchHandoverCommand(
        String idempotencyKey,
        UUID materialId,
        UUID departmentId,
        UUID employeeId,
        BigDecimal quantity,
        BigDecimal unitCost,
        BigDecimal totalValue,
        String uom,
        String originReference,
        String handoverNumber
    ) {
        public DispatchHandoverCommand {
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new BadRequestException("Idempotency key is required for handover dispatch");
            }
            if (materialId == null) {
                throw new BadRequestException("Material ID is required for handover dispatch");
            }
            if (quantity == null || quantity.signum() <= 0) {
                throw new BadRequestException("Handover quantity must be strictly positive");
            }
            if (departmentId == null && employeeId == null) {
                throw new BadRequestException("At least department or employee must be designated as holder");
            }
        }
    }

    public record AcknowledgeHandoverCommand(
        UUID handoverId,
        UUID acknowledgedByEmployeeId,
        String note,
        BigDecimal acceptedQuantity,
        BigDecimal discrepancyQuantity
    ) {
        public AcknowledgeHandoverCommand(UUID handoverId, UUID acknowledgedByEmployeeId, String note) {
            this(handoverId, acknowledgedByEmployeeId, note, null, null);
        }

        public AcknowledgeHandoverCommand {
            if (handoverId == null) {
                throw new BadRequestException("Handover ID is required for acknowledgement");
            }
        }
    }

    public record RejectHandoverCommand(
        UUID handoverId,
        String rejectionReason
    ) {
        public RejectHandoverCommand {
            if (handoverId == null) {
                throw new BadRequestException("Handover ID is required for rejection");
            }
            if (rejectionReason == null || rejectionReason.isBlank()) {
                throw new BadRequestException("Rejection reason is required");
            }
        }
    }

    public record HistoricalHoldingActivationCommand(
        String idempotencyKey,
        UUID materialId,
        UUID departmentId,
        UUID employeeId,
        BigDecimal quantity,
        BigDecimal unitCost,
        BigDecimal totalValue,
        String uom,
        String sourceKey,
        LocalDate sourceDocumentDate
    ) {
        public HistoricalHoldingActivationCommand {
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new BadRequestException("Idempotency key is required for historical holding activation");
            }
            if (materialId == null) {
                throw new BadRequestException("Material ID is required for historical holding activation");
            }
            if (quantity == null || quantity.signum() <= 0) {
                throw new BadRequestException("Holding quantity must be strictly positive");
            }
            if (departmentId == null && employeeId == null) {
                throw new BadRequestException("At least department or employee must be designated as holder");
            }
        }
    }

    public record HoldingHandoverResult(
        UUID handoverId,
        String handoverNumber,
        UUID holdingId,
        HandoverStatus handoverStatus,
        HoldingStatus holdingStatus,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal pendingQuantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal activeQuantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unitCost,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalValue,
        List<String> allowedActions
    ) {
        public HoldingHandoverResult(
            UUID handoverId,
            String handoverNumber,
            UUID holdingId,
            HandoverStatus handoverStatus,
            HoldingStatus holdingStatus,
            BigDecimal pendingQuantity,
            BigDecimal activeQuantity,
            BigDecimal unitCost,
            BigDecimal totalValue
        ) {
            this(handoverId, handoverNumber, holdingId, handoverStatus, holdingStatus, pendingQuantity, activeQuantity, unitCost, totalValue, computeAllowedActions(handoverStatus));
        }

        public static java.util.List<String> computeAllowedActions(HandoverStatus status) {
            if (status == null) return java.util.List.of();
            return switch (status) {
                case PENDING -> java.util.List.of("ACKNOWLEDGE", "REJECT", "CANCEL");
                case ACKNOWLEDGED, PARTIALLY_ACKNOWLEDGED -> java.util.List.of("TRANSFER", "RETURN_TO_WAREHOUSE", "CONSUME", "WRITE_OFF");
                case DISCREPANCY_REVIEW -> java.util.List.of("ACKNOWLEDGE", "REJECT");
                case REJECTED, CANCELLED -> java.util.List.of();
            };
        }
    }

    public record HistoricalActivationResult(
        UUID holdingId,
        String idempotencyKey,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal activeQuantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal pendingQuantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalValue,
        HoldingOriginType originType,
        boolean replayed
    ) {}

    public record ConsumeHoldingCommand(
        String idempotencyKey,
        UUID holdingId,
        BigDecimal quantity,
        String reason,
        LocalDate consumptionDate
    ) {
        public ConsumeHoldingCommand {
            if (holdingId == null) {
                throw new BadRequestException("Holding ID is required for consumption");
            }
            if (quantity == null || quantity.signum() <= 0) {
                throw new BadRequestException("Consumption quantity must be strictly positive");
            }
        }
    }

    public record WriteOffHoldingCommand(
        String idempotencyKey,
        UUID holdingId,
        BigDecimal quantity,
        String reason,
        String actNumber,
        LocalDate writeOffDate
    ) {
        public WriteOffHoldingCommand {
            if (holdingId == null) {
                throw new BadRequestException("Holding ID is required for write-off");
            }
            if (quantity == null || quantity.signum() <= 0) {
                throw new BadRequestException("Write-off quantity must be strictly positive");
            }
        }
    }

    public record ReturnHoldingCommand(
        String idempotencyKey,
        UUID holdingId,
        UUID warehouseId,
        BigDecimal quantity,
        String targetAccountCode,
        String condition,
        BigDecimal unitCost
    ) {
        public ReturnHoldingCommand {
            if (holdingId == null) {
                throw new BadRequestException("Holding ID is required for return");
            }
            if (warehouseId == null) {
                throw new BadRequestException("Destination warehouse ID is required for return");
            }
            if (quantity == null || quantity.signum() <= 0) {
                throw new BadRequestException("Return quantity must be strictly positive");
            }
        }
    }

    public record HoldingConsumptionResult(
        UUID holdingId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal consumedQuantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal remainingActiveQuantity,
        boolean financialExpenseRecorded,
        String expensePolicy
    ) {}

    public record HoldingWriteOffResult(
        UUID holdingId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal writtenOffQuantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal remainingActiveQuantity,
        boolean financialExpenseRecorded,
        String actNumber
    ) {}

    public record HoldingReturnResult(
        UUID holdingId,
        UUID warehouseId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal returnedQuantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal remainingActiveQuantity,
        String targetAccountCode,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal restoredStockQuantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal restoredStockValue
    ) {}

    public record TransferHoldingCommand(
        String idempotencyKey,
        UUID sourceHoldingId,
        UUID targetDepartmentId,
        UUID targetEmployeeId,
        BigDecimal quantity,
        String reason,
        String transferNumber
    ) {
        public TransferHoldingCommand {
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new BadRequestException("Idempotency key is required for holding transfer");
            }
            if (sourceHoldingId == null) {
                throw new BadRequestException("Source holding ID is required for transfer");
            }
            if (quantity == null || quantity.signum() <= 0) {
                throw new BadRequestException("Transfer quantity must be strictly positive");
            }
            if (targetDepartmentId == null && targetEmployeeId == null) {
                throw new BadRequestException("Target department or employee must be designated");
            }
        }
    }

    public record HoldingTransferResult(
        UUID transferId,
        String transferNumber,
        UUID sourceHoldingId,
        UUID targetHoldingId,
        UUID materialId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal transferredQuantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal sourceRemainingActiveQuantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal targetActiveQuantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalConservedQuantity,
        List<String> allowedActions
    ) {
        public HoldingTransferResult(
            UUID transferId,
            String transferNumber,
            UUID sourceHoldingId,
            UUID targetHoldingId,
            UUID materialId,
            BigDecimal transferredQuantity,
            BigDecimal sourceRemainingActiveQuantity,
            BigDecimal targetActiveQuantity,
            BigDecimal totalConservedQuantity
        ) {
            this(transferId, transferNumber, sourceHoldingId, targetHoldingId, materialId, transferredQuantity, sourceRemainingActiveQuantity, targetActiveQuantity, totalConservedQuantity, java.util.List.of("CONSUME", "RETURN_TO_WAREHOUSE", "WRITE_OFF"));
        }
    }

    public record HoldingStatementQuery(
        UUID materialId,
        UUID departmentId,
        UUID employeeId,
        String accountCode
    ) {}

    public record HoldingStatementPosition(
        UUID holdingId,
        UUID materialId,
        UUID departmentId,
        UUID employeeId,
        String accountCode,
        String uom,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal pendingQuantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal activeQuantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal consumedQuantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal returnedQuantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal writtenOffQuantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal transferredInQuantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal transferredOutQuantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal calculatedClosingQuantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantityDelta,
        boolean isBalanced
    ) {}

    public record HoldingStatementReport(
        int totalHoldings,
        int balancedHoldings,
        int discrepancyHoldings,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalActiveQuantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalPendingQuantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalConsumedQuantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalReturnedQuantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalWrittenOffQuantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalTransferredInQuantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalTransferredOutQuantity,
        boolean isFullyBalanced,
        List<HoldingStatementPosition> positions
    ) {}
}
