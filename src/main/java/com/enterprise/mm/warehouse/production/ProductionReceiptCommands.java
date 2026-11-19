package com.enterprise.mm.warehouse.production;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public class ProductionReceiptCommands {

    public record ProductionReceiptEvent(
        String eventId,
        int eventVersion,
        UUID materialId,
        UUID warehouseId,
        BigDecimal quantity,
        String baseUnitCode,
        BigDecimal unitCost,
        BigDecimal totalCost,
        String accountCode,
        String qualityDisposition,
        LocalDate receiptDate,
        String productionOrderRef
    ) {}

    public record ProductionReceiptResult(
        UUID receiptId,
        String eventId,
        String postingReceiptId,
        BigDecimal quantity,
        BigDecimal totalCost,
        boolean isDuplicate
    ) {}

    public record ProductionCorrectionCommand(
        String parentEventId,
        String correctionEventId,
        int eventVersion,
        BigDecimal deltaQuantity,
        BigDecimal deltaCost,
        String reason
    ) {}

    public record ProductionCorrectionResult(
        UUID correctionId,
        String parentEventId,
        String correctionEventId,
        BigDecimal deltaQuantity,
        BigDecimal deltaCost,
        String postingReceiptId
    ) {}

    public record ProductionReconciliationReport(
        UUID materialId,
        UUID warehouseId,
        BigDecimal totalOriginalQuantity,
        BigDecimal totalDeltaQuantity,
        BigDecimal netReconstructedQuantity,
        BigDecimal totalOriginalCost,
        BigDecimal totalDeltaCost,
        BigDecimal netReconstructedCost
    ) {}
}
