package com.enterprise.mm.warehouse.receiving.model;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.List;
import com.enterprise.mm.enums.TrackingMode;

public record ReceiptLinePostingDraft(
    UUID purchaseOrderItemId,
    UUID materialId,
    UUID materialGroupId,
    String uomCode,
    UUID warehouseId,
    UUID storageLocationId,
    UUID storageBinId,
    String batchNumber,
    BigDecimal openQuantityBefore,
    BigDecimal physicalReceivedQuantity,
    BigDecimal acceptedQuantity,
    BigDecimal rejectedQuantity,
    BigDecimal damagedQuantity,
    BigDecimal excessQuantity,
    BigDecimal shortageQuantity,
    String shortageReason,
    String qualityStatus,
    UUID stockMovementId,
    UUID stockBatchId,
    TrackingMode trackingMode,
    List<ReceiptUnitPostingDraft> units,
    String accreditationStatus,
    String accreditationMessage,
    UUID accreditationExceptionId,
    List<ReceiptBatchPostingDraft> batches
) {
    public ReceiptLinePostingDraft {
        units = units == null ? List.of() : List.copyOf(units);
        batches = batches == null ? List.of() : List.copyOf(batches);
    }

    public ReceiptLinePostingDraft(
        UUID purchaseOrderItemId,
        UUID materialId,
        UUID materialGroupId,
        String uomCode,
        UUID warehouseId,
        UUID storageLocationId,
        UUID storageBinId,
        String batchNumber,
        BigDecimal openQuantityBefore,
        BigDecimal physicalReceivedQuantity,
        BigDecimal acceptedQuantity,
        BigDecimal rejectedQuantity,
        BigDecimal damagedQuantity,
        BigDecimal excessQuantity,
        BigDecimal shortageQuantity,
        String shortageReason,
        String qualityStatus,
        UUID stockMovementId,
        UUID stockBatchId,
        TrackingMode trackingMode,
        List<ReceiptUnitPostingDraft> units,
        String accreditationStatus,
        String accreditationMessage,
        UUID accreditationExceptionId
    ) {
        this(
            purchaseOrderItemId,
            materialId,
            materialGroupId,
            uomCode,
            warehouseId,
            storageLocationId,
            storageBinId,
            batchNumber,
            openQuantityBefore,
            physicalReceivedQuantity,
            acceptedQuantity,
            rejectedQuantity,
            damagedQuantity,
            excessQuantity,
            shortageQuantity,
            shortageReason,
            qualityStatus,
            stockMovementId,
            stockBatchId,
            trackingMode,
            units,
            accreditationStatus,
            accreditationMessage,
            accreditationExceptionId,
            List.of()
        );
    }

    public ReceiptLinePostingDraft(
        UUID purchaseOrderItemId,
        UUID materialId,
        UUID materialGroupId,
        String uomCode,
        UUID warehouseId,
        UUID storageLocationId,
        UUID storageBinId,
        String batchNumber,
        BigDecimal openQuantityBefore,
        BigDecimal acceptedQuantity,
        BigDecimal rejectedQuantity,
        BigDecimal damagedQuantity,
        BigDecimal excessQuantity,
        BigDecimal shortageQuantity,
        String shortageReason,
        String qualityStatus,
        UUID stockMovementId,
        UUID stockBatchId,
        TrackingMode trackingMode,
        List<ReceiptUnitPostingDraft> units,
        String accreditationStatus,
        String accreditationMessage,
        UUID accreditationExceptionId,
        List<ReceiptBatchPostingDraft> batches
    ) {
        this(
            purchaseOrderItemId,
            materialId,
            materialGroupId,
            uomCode,
            warehouseId,
            storageLocationId,
            storageBinId,
            batchNumber,
            openQuantityBefore,
            nvl(acceptedQuantity).add(nvl(rejectedQuantity)).add(nvl(damagedQuantity)),
            acceptedQuantity,
            rejectedQuantity,
            damagedQuantity,
            excessQuantity,
            shortageQuantity,
            shortageReason,
            qualityStatus,
            stockMovementId,
            stockBatchId,
            trackingMode,
            units,
            accreditationStatus,
            accreditationMessage,
            accreditationExceptionId,
            batches
        );
    }

    public ReceiptLinePostingDraft(
        UUID purchaseOrderItemId,
        UUID materialId,
        UUID materialGroupId,
        String uomCode,
        UUID warehouseId,
        UUID storageLocationId,
        UUID storageBinId,
        String batchNumber,
        BigDecimal openQuantityBefore,
        BigDecimal acceptedQuantity,
        BigDecimal rejectedQuantity,
        BigDecimal damagedQuantity,
        BigDecimal excessQuantity,
        BigDecimal shortageQuantity,
        String shortageReason,
        String qualityStatus,
        UUID stockMovementId,
        UUID stockBatchId,
        TrackingMode trackingMode,
        List<ReceiptUnitPostingDraft> units,
        String accreditationStatus,
        String accreditationMessage,
        UUID accreditationExceptionId
    ) {
        this(
            purchaseOrderItemId,
            materialId,
            materialGroupId,
            uomCode,
            warehouseId,
            storageLocationId,
            storageBinId,
            batchNumber,
            openQuantityBefore,
            acceptedQuantity,
            rejectedQuantity,
            damagedQuantity,
            excessQuantity,
            shortageQuantity,
            shortageReason,
            qualityStatus,
            stockMovementId,
            stockBatchId,
            trackingMode,
            units,
            accreditationStatus,
            accreditationMessage,
            accreditationExceptionId,
            List.of()
        );
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
