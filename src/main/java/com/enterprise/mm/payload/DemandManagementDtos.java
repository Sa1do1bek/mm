package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class DemandManagementDtos {
    private DemandManagementDtos() {
    }

    public record DemandActionRequest(String comment) {
    }

    public record DemandPurchaseRequestResult(UUID demandPlanId, int purchaseRequestCount, List<UUID> purchaseRequestIds) {
    }

    public record DemandCyclePlanGenerationRequest(List<UUID> departmentIds, UUID budgetItemId, String ownerUsername) {
    }

    public record DemandCyclePlanGenerationResult(UUID planningCycleId, int createdCount, int existingCount, List<UUID> demandPlanIds) {
    }

    public record DemandReservationRequest(UUID warehouseId, UUID storageLocationId, UUID stockBatchId, BigDecimal quantity) {
    }

    public record ReleaseReservationRequest(BigDecimal quantity, String comment, @NotNull Long expectedVersion, List<UUID> materialUnitIds) {
        public ReleaseReservationRequest(BigDecimal quantity, String comment, @NotNull Long expectedVersion) {
            this(quantity, comment, expectedVersion, List.of());
        }
    }
}
