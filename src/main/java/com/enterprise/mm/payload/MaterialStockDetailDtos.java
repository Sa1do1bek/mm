package com.enterprise.mm.payload;

import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.payload.MaterialCommandViewDtos.DecimalMetric;
import com.enterprise.mm.payload.MaterialCommandViewDtos.MoneyMetric;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Read-only detail shared by stock lists, material pickers and document lines. */
public final class MaterialStockDetailDtos {
    private MaterialStockDetailDtos() { }

    public enum DataStatus { AVAILABLE, UNAVAILABLE }
    public enum ProvenanceStatus { VERIFIED, PARTIAL, UNKNOWN }
    public enum QuantityBasis { COST_LAYER_ALLOCATION }

    public record Filter(UUID warehouseId, UUID stockBatchId, LocalDate asOf, boolean positiveOnly) { }
    public record MaterialInfo(UUID id, String code, String name, UUID unitOfMeasureId, String unitOfMeasureShortName) {
        public MaterialInfo(UUID id, String code, String name, UUID unitOfMeasureId) {
            this(id, code, name, unitOfMeasureId, null);
        }
        public MaterialInfo withUnitShortName(String value) {
            return new MaterialInfo(id, code, name, unitOfMeasureId, value);
        }
 }
    public record Permissions(boolean canViewCost, boolean canViewSupplier, boolean canViewDocuments) { }
    public record Party(UUID id, String code, String name) { }
    public record Document(String type, UUID id, String code) { }

    public record Summary(
        DecimalMetric onHand, DecimalMetric available, DecimalMetric reserved, DecimalMetric quarantine,
        MoneyMetric totalBookValue, DecimalMetric costLayerQuantity, DecimalMetric quantityWithoutCostLayers,
        DecimalMetric costLayerQuantityDifference,
        MoneyMetric costLayerBookValue, MoneyMetric bookValueDifference
    ) { }

    public record Origin(
        UUID costLayerId, UUID warehouseId, String warehouseName, UUID stockBatchId, String batchNumber,
        String layerType, String receivedQuantity, String remainingQuantity,
        LocalDate warehouseReceivedDate, LocalDate originalReceivedDate,
        UUID originalMovementId, Party sourceWarehouse, Party supplier, Document receiptDocument,
        MoneyMetric purchaseUnitPrice, String purchaseExchangeRate,
        MoneyMetric receiptUnitCost, MoneyMetric currentUnitCost, MoneyMetric remainingBookValue,
        ProvenanceStatus provenanceStatus, QuantityBasis quantityBasis
    ) { }

    public record Detail(
        MaterialInfo material, UUID warehouseId, UUID stockBatchId, LocalDate asOf, Instant calculatedAt,
        boolean historical, DataStatus dataStatus, Permissions permissions, ValuationMethod valuationMethod,
        QuantityBasis quantityBasis, Summary summary, PageResponse<Origin> origins, List<String> warnings
    ) { }

    public record Allocation(
        UUID id, int sequence, String role, UUID warehouseId, UUID stockBatchId, String batchNumber,
        UUID storageLocationId, UUID storageBinId, String onHandDelta, String availableDelta,
        String reservedDelta, String quarantineDelta, MoneyMetric bookValueDelta, MoneyMetric unitCost,
        List<CostAllocation> costLayers
    ) { }

    public record CostAllocation(UUID costLayerId, UUID parentCostLayerId, String role,
        String quantityDelta, MoneyMetric valueDelta, MoneyMetric unitCost) { }

    public record MovementDetail(UUID movementId, UUID materialId, String status, String documentQuantity,
        Permissions permissions, DataStatus allocationStatus, PageResponse<Allocation> allocations) { }
}
