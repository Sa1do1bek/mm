package com.enterprise.mm.payload;

import java.util.List;
import java.util.UUID;

public final class InventoryWorkspaceDtos {
    private InventoryWorkspaceDtos() {}
    public record StockKeyOption(UUID lineId, String materialName, String unitCode, String warehouseName,
        String locationName, String binName, String batchNumber, String serialNumber, int quantityScale, String unitShortName) {
        public StockKeyOption(UUID lineId, String materialName, String unitCode, String warehouseName,
        String locationName, String binName, String batchNumber, String serialNumber, int quantityScale) {
            this(lineId, materialName, unitCode, warehouseName, locationName, binName, batchNumber, serialNumber, quantityScale, null);
        }
        public StockKeyOption withUnitShortName(String value) {
            return new StockKeyOption(lineId, materialName, unitCode, warehouseName, locationName, binName, batchNumber, serialNumber, quantityScale, value);
        }
    }
    public record Capabilities(boolean canCount, boolean canReview, boolean canSeeSystemQuantity,
        boolean canApprove, boolean canPost, boolean canReturnForCorrection, boolean canDecide, boolean canValueFoundStock, boolean canPlan) {}
    public record Action(String code, boolean enabled, String disabledReason) {}
    public record FreezeScope(boolean active, boolean freezeOperations, boolean globalWarehouse,
        UUID warehouseId, String warehouseName, UUID locationId, String locationName, UUID binId,
        String binName, List<UUID> materialIds, List<UUID> materialGroupIds,
        com.enterprise.mm.enums.InventoryScopeMode scopeMode, List<UUID> departmentIds, List<UUID> warehouseIds) {}
    public record BlockingCount(UUID id, String code, String name) {}
    public record Context(InventoryDocumentResponse document, List<InventoryItemResponse> lines,
        Capabilities capabilities, List<Action> actions, List<String> blockers, FreezeScope freezeScope,
        List<UUID> postingMovementIds, List<InventoryLineHistoryResponse> history, long countRevision,
        boolean workspaceEnabled, List<BlockingCount> scopeConflicts, com.enterprise.mm.service.inventory.InventoryPostingReadinessService.Readiness postingReadiness) {}
}
