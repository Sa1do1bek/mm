package com.enterprise.mm.warehouse.production;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public final class ProductionOperationDtos {
    private ProductionOperationDtos() {}
    public enum OperationType { MANUFACTURING, PROCESSING, PACKAGING, ASSEMBLY, KITTING, CUTTING, DISASSEMBLY }
    public record Input(UUID materialId, UUID stockPositionId, BigDecimal quantity,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) List<UUID> materialUnitIds) {
        public Input(UUID materialId, UUID stockPositionId, BigDecimal quantity) { this(materialId, stockPositionId, quantity, null); }
    }
    /** Weights distribute actual consumed value plus additional costs; quantities never imply a unit conversion. */
    public record Output(UUID materialId, BigDecimal quantity, BigDecimal costWeight, String batchNumber, String lineType,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) List<String> serialNumbers) {
        public Output(UUID materialId, BigDecimal quantity, BigDecimal costWeight, String batchNumber, String lineType) {
            this(materialId, quantity, costWeight, batchNumber, lineType, null);
        }
    }
    public record Draft(String operationNumber, OperationType operationType, UUID warehouseId,
        LocalDate operationDate, String reference, String notes, BigDecimal additionalCost,
        String additionalCostReason, List<Input> inputs, List<Output> outputs) {}
    public record VersionCommand(@NotNull @PositiveOrZero Long version) {}
    public record ReversalCommand(@NotNull @PositiveOrZero Long version, String reason) {}
    public record Line(UUID id, String lineType, UUID materialId, String materialCode, String materialName,
        String unitCode, BigDecimal quantity, BigDecimal allocatedCost, UUID stockPositionId,
        UUID stockBatchId, UUID movementId, UUID reversalMovementId, List<Unit> units, UUID qualityInspectionId, String qualityStatus, String unitShortName) {
        public Line(UUID id, String lineType, UUID materialId, String materialCode, String materialName,
        String unitCode, BigDecimal quantity, BigDecimal allocatedCost, UUID stockPositionId,
        UUID stockBatchId, UUID movementId, UUID reversalMovementId, List<Unit> units, UUID qualityInspectionId, String qualityStatus) {
            this(id, lineType, materialId, materialCode, materialName, unitCode, quantity, allocatedCost, stockPositionId, stockBatchId, movementId, reversalMovementId, units, qualityInspectionId, qualityStatus, null);
        }
        public Line withUnitShortName(String value) {
            return new Line(id, lineType, materialId, materialCode, materialName, unitCode, quantity, allocatedCost, stockPositionId, stockBatchId, movementId, reversalMovementId, units, qualityInspectionId, qualityStatus, value);
        }
    }
    public record Unit(UUID id, String serialNumber, String status) {}
    public record Operation(UUID id, String operationNumber, OperationType operationType, UUID warehouseId,
        String status, long version, LocalDate operationDate, Draft draft, BigDecimal inputCost,
        BigDecimal additionalCost, BigDecimal outputCost, String createdBy, Instant createdAt,
        String postedBy, Instant postedAt, String reversalReason, List<Line> lines, List<String> allowedActions, boolean canViewCost) {}
    public record HistoryNode(UUID movementId, UUID materialId, String materialCode, String materialName,
        UUID warehouseId, String warehouseName, UUID stockBatchId, String batchNumber, String unitCode,
        String movementType, BigDecimal quantity, LocalDate date, String referenceType,
        UUID referenceId, String documentNumber, String status, List<UUID> parents, String unitShortName) {
        public HistoryNode(UUID movementId, UUID materialId, String materialCode, String materialName,
        UUID warehouseId, String warehouseName, UUID stockBatchId, String batchNumber, String unitCode,
        String movementType, BigDecimal quantity, LocalDate date, String referenceType,
        UUID referenceId, String documentNumber, String status, List<UUID> parents) {
            this(movementId, materialId, materialCode, materialName, warehouseId, warehouseName, stockBatchId, batchNumber, unitCode, movementType, quantity, date, referenceType, referenceId, documentNumber, status, parents, null);
        }
        public HistoryNode withUnitShortName(String value) {
            return new HistoryNode(movementId, materialId, materialCode, materialName, warehouseId, warehouseName, stockBatchId, batchNumber, unitCode, movementType, quantity, date, referenceType, referenceId, documentNumber, status, parents, value);
        }
    }
    public record History(List<HistoryNode> nodes, boolean truncated) {}
}
