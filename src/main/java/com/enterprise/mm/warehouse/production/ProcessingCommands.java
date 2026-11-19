package com.enterprise.mm.warehouse.production;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class ProcessingCommands {

    public enum TransformationType {
        CUTTING,
        ASSEMBLY,
        DISASSEMBLY,
        PROCESSOR_TOLLING
    }

    public enum TransformationOrderStatus {
        PLANNED,
        DISPATCHED_TO_PROCESSOR,
        PARTIALLY_RETURNED,
        EXECUTED,
        CANCELLED
    }

    public enum TransformationLineType {
        INPUT,
        OUTPUT,
        SCRAP,
        BYPRODUCT,
        RETURNED
    }

    public record TransformationLineItem(
        TransformationLineType lineType,
        UUID materialId,
        String accountCode,
        BigDecimal quantity,
        BigDecimal unitCost,
        BigDecimal allocatedCost
    ) {}

    public record ExecuteTransformationCommand(
        String orderNumber,
        TransformationType transformationType,
        UUID warehouseId,
        UUID processorId,
        List<TransformationLineItem> inputs,
        List<TransformationLineItem> outputs,
        LocalDate executionDate,
        String sourceReference
    ) {}

    public record DispatchToProcessorCommand(
        String orderNumber,
        UUID warehouseId,
        UUID processorId,
        UUID materialId,
        String accountCode,
        BigDecimal quantity,
        BigDecimal unitCost,
        LocalDate dispatchDate,
        String sourceReference
    ) {}

    public record ConfirmProcessorConsumptionCommand(
        String orderNumber,
        UUID processorId,
        BigDecimal consumedQuantity,
        List<TransformationLineItem> outputs,
        LocalDate confirmationDate,
        String sourceReference
    ) {}

    public record ReturnFromProcessorCommand(
        String orderNumber,
        UUID warehouseId,
        UUID materialId,
        BigDecimal returnedQuantity,
        BigDecimal unitCost,
        LocalDate returnDate,
        String reason,
        String sourceReference
    ) {}

    public record TransformationResult(
        UUID orderId,
        String orderNumber,
        TransformationType transformationType,
        TransformationOrderStatus status,
        BigDecimal totalInputCost,
        BigDecimal totalOutputCost,
        List<String> allowedActions
    ) {
        public TransformationResult(
            UUID orderId,
            String orderNumber,
            TransformationType transformationType,
            TransformationOrderStatus status,
            BigDecimal totalInputCost,
            BigDecimal totalOutputCost
        ) {
            this(orderId, orderNumber, transformationType, status, totalInputCost, totalOutputCost, computeAllowedActions(status));
        }

        public static List<String> computeAllowedActions(TransformationOrderStatus status) {
            if (status == null) return List.of();
            return switch (status) {
                case PLANNED -> List.of("EXECUTE", "DISPATCH_TO_PROCESSOR", "CANCEL");
                case DISPATCHED_TO_PROCESSOR -> List.of("CONFIRM_CONSUMPTION", "RETURN_FROM_PROCESSOR", "CANCEL");
                case PARTIALLY_RETURNED -> List.of("CONFIRM_CONSUMPTION", "RETURN_FROM_PROCESSOR");
                case EXECUTED, CANCELLED -> List.of();
            };
        }
    }
}
