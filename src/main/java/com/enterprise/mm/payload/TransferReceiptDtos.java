package com.enterprise.mm.payload;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class TransferReceiptDtos {
    private TransferReceiptDtos() {}

    public enum ReceiptMode { PARTIAL, FULL_SELECTED_ROWS }

    public record LocationOption(UUID id, String name, boolean binRequired) {}

    public record Context(Long expectedVersion, LocalDate defaultReceivedDate, ReceiptMode defaultReceiptMode,
        boolean canReceive, String disabledReason, UUID destinationWarehouseId, List<LocationOption> locations, List<Row> rows) {}

    public record Row(
        @Schema(description = "Opaque stable receipt row identity; submit unchanged. Server resolves its allocations within this transfer.")
        String rowKey, UUID transferLineId, UUID shipmentId, UUID sourceStockBatchId,
        UUID materialId, String materialName, String batchNumber, String serialNumber, String unitCode, String unitLabel,
        BigDecimal plannedQuantity, BigDecimal dispatchedQuantity, BigDecimal receivedQuantityToDate,
        BigDecimal maxReceivableQuantity, int quantityScale, boolean serialSelectionRequired,
        UUID defaultStorageLocationId, boolean locationRequired, boolean binRequired, boolean editable, String disabledReason) {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "unitShortName", access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public String unitShortName() { return com.enterprise.mm.common.UnitOfMeasureLabels.shortLabel(unitLabel); }
}
}
