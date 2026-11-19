package com.enterprise.mm.payload;

import com.enterprise.mm.enums.PickingMethod;
import com.enterprise.mm.enums.StockAllocationMode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class TransferPickingDtos {
    private TransferPickingDtos() {}
    public record SerialUnit(UUID id, String serialNumber) {}
    public record Position(UUID stockBalanceId, UUID stockBatchId, String batchNumber,
        UUID storageLocationId, UUID storageBinId, LocalDate receivedDate, LocalDate expiryDate,
        BigDecimal onHandQuantity, BigDecimal availableQuantity, BigDecimal executableQuantity,
        boolean selectable, String blockedReason, List<SerialUnit> materialUnits) {}
    public record StockOptions(UUID transferLineId, PickingMethod pickingMethod, int page, int size,
        long totalElements, List<Position> positions) {}
    public record LineContext(UUID transferLineId, UUID materialId, String materialName,
        UUID unitOfMeasureId, String unitCode, int quantityScale, BigDecimal remainingQuantity,
        BigDecimal eligibleAvailableQuantity, BigDecimal maxDispatchQuantity, PickingMethod defaultPickingMethod, String unitShortName) {
        public LineContext(UUID transferLineId, UUID materialId, String materialName,
        UUID unitOfMeasureId, String unitCode, int quantityScale, BigDecimal remainingQuantity,
        BigDecimal eligibleAvailableQuantity, BigDecimal maxDispatchQuantity, PickingMethod defaultPickingMethod) {
            this(transferLineId, materialId, materialName, unitOfMeasureId, unitCode, quantityScale, remainingQuantity, eligibleAvailableQuantity, maxDispatchQuantity, defaultPickingMethod, null);
        }
        public LineContext withUnitShortName(String value) {
            return new LineContext(transferLineId, materialId, materialName, unitOfMeasureId, unitCode, quantityScale, remainingQuantity, eligibleAvailableQuantity, maxDispatchQuantity, defaultPickingMethod, value);
        }
    }
    public record Context(UUID transferDocumentId, Long expectedVersion, LocalDateTime calculatedAt,
        List<LineContext> lines) {}
    public record SelectedAllocation(UUID stockBalanceId, UUID stockBatchId, UUID storageLocationId,
        UUID storageBinId, BigDecimal quantity, List<UUID> materialUnitIds) {}
    /** Immutable command decision; the posted shipment allocations remain the source of actual cost evidence. */
    public record Decision(UUID transferLineId, PickingMethod pickingMethod, StockAllocationMode allocationMode,
        BigDecimal quantity, List<SelectedAllocation> allocations) {}
    public record Preview(UUID transferDocumentId, Long expectedVersion, LocalDateTime calculatedAt,
        List<Decision> lines) {}
}
