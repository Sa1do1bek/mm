package com.enterprise.mm.inventory.availability.payload;

import com.enterprise.mm.inventory.availability.enums.DemandUrgencyBucket;
import com.enterprise.mm.inventory.availability.enums.InventoryAvailabilityQueueKey;
import com.enterprise.mm.inventory.availability.enums.WarehouseScopeMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class InventoryAvailabilityDtos {
    private InventoryAvailabilityDtos() {
    }

    public record InventoryAvailabilityFilter(
            InventoryAvailabilityQueueKey queue,
            String search,
            UUID warehouseId,
            UUID materialGroupId,
            Boolean replacementMissing,
            Boolean staleBalance,
            Boolean nonMoving,
            Boolean negativeProjected,
            DemandUrgencyBucket urgency,
            String materialCode,
            String materialName,
            UUID materialId,
            List<UUID> warehouseIds,
            LocalDate asOfDate,
            LocalDate dateFrom,
            LocalDate dateTo
    ) {
        public InventoryAvailabilityFilter(
                InventoryAvailabilityQueueKey queue,
                String search,
                UUID warehouseId,
                UUID materialGroupId,
                Boolean replacementMissing,
                Boolean staleBalance,
                Boolean nonMoving,
                Boolean negativeProjected,
                DemandUrgencyBucket urgency
        ) {
            this(queue, search, warehouseId, materialGroupId, replacementMissing,
                 staleBalance, nonMoving, negativeProjected, urgency,
                 null, null, null, null, null, null, null);
        }

        public InventoryAvailabilityFilter normalized() {
            List<UUID> cleanWarehouseIds = (warehouseIds == null || warehouseIds.isEmpty())
                    ? null
                    : warehouseIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
            if (cleanWarehouseIds != null && cleanWarehouseIds.isEmpty()) {
                cleanWarehouseIds = null;
            }

            return new InventoryAvailabilityFilter(
                    queue,
                    search == null || search.isBlank() ? null : search.trim(),
                    warehouseId,
                    materialGroupId,
                    Boolean.TRUE.equals(replacementMissing) ? Boolean.TRUE : null,
                    Boolean.TRUE.equals(staleBalance) ? Boolean.TRUE : null,
                    Boolean.TRUE.equals(nonMoving) ? Boolean.TRUE : null,
                    Boolean.TRUE.equals(negativeProjected) ? Boolean.TRUE : null,
                    urgency,
                    materialCode == null || materialCode.isBlank() ? null : materialCode.trim(),
                    materialName == null || materialName.isBlank() ? null : materialName.trim(),
                    materialId,
                    cleanWarehouseIds,
                    asOfDate,
                    dateFrom,
                    dateTo);
        }
    }

    public record InventoryAvailabilityRow(
            UUID materialId,
            String materialCode,
            String materialName,
            String materialStatus,
            UUID materialGroupId,
            String materialGroupName,
            String unitOfMeasureCode,
            String unitOfMeasureShortName,
            String unitOfMeasureShortNameRu,
            String unitOfMeasureShortNameUz,
            String unitOfMeasureName,
            String unitOfMeasureNameRu,
            String unitOfMeasureNameUz,
            UUID replacementMaterialId,
            String replacementMaterialCode,
            String replacementMaterialName,
            BigDecimal onHand,
            BigDecimal available,
            BigDecimal reserved,
            BigDecimal quarantine,
            BigDecimal openDemand,
            long demandDocumentCount,
            BigDecimal openSupply,
            long supplyDocumentCount,
            LocalDate nearestDemandDate,
            LocalDate nearestSupplyDate,
            DemandUrgencyBucket demandUrgencyBucket,
            DemandUrgencyBucket supplyUrgencyBucket,
            BigDecimal projectedAvailable,
            BigDecimal shortage,
            BigDecimal minStock,
            BigDecimal safetyStock,
            BigDecimal reorderPoint,
            BigDecimal maxStock,
            BigDecimal totalValue,
            long warehouseCount,
            long locationCount,
            long batchCount,
            LocalDateTime balanceAt,
            InventoryAvailabilityQueueKey signal,
            List<String> signalReasons,
            WarehouseScopeMode warehouseScopeMode
    ) {
    }

    public record InventoryAvailabilityOverview(
            long materialCount,
            long shortageCount,
            long belowSafetyCount,
            long quarantineBlockedCount,
            long reorderNeededCount,
            long excessCount,
            long healthyCount,
            long staleDataCount,
            BigDecimal shortageVolume,
            BigDecimal quarantineVolume,
            BigDecimal belowSafetyDeficit,
            BigDecimal reorderNeededDeficit,
            BigDecimal excessSurplus,
            BigDecimal healthyTotalValue,
            BigDecimal totalOnHand,
            BigDecimal totalAvailable,
            BigDecimal totalReserved,
            BigDecimal totalQuarantine,
            BigDecimal totalShortage,
            BigDecimal totalValue,
            Instant generatedAt
    ) {
    }

    public record InventoryAvailabilityQueueDefinition(
            InventoryAvailabilityQueueKey key,
            String titleKey,
            long count,
            InventoryAvailabilityFilter appliedFilters
    ) {
    }

    public record InventoryAvailabilityWorkspaceResponse(
            InventoryAvailabilityOverview overview,
            List<InventoryAvailabilityQueueDefinition> queues
    ) {
    }
}
