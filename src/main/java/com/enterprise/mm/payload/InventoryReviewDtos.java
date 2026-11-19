package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class InventoryReviewDtos {
    private InventoryReviewDtos() {}
    public record Decision(Long expectedLineVersion, Long countRevision, String decisionType,
        String reason, List<UUID> evidenceIds, UUID sourceCorrectionMovementId) {}
    public record Recount(Long expectedLineVersion, String reason) {}
    public record ReturnForCorrection(Long expectedDocumentVersion, String reason) {}
    public record FoundStock(Long expectedDocumentVersion, UUID materialId, UUID warehouseId,
        UUID storageLocationId, UUID storageBinId, UUID stockBatchId, String batchNumber,
        String serialNumber, LocalDate expiryDate, BigDecimal countedQuantity, String reason,
        List<UUID> evidenceIds, UUID materialMasterRequestId) {}
    public record UnidentifiedStock(Long expectedDocumentVersion, UUID materialMasterRequestId,
        String observedName, UUID warehouseId, UUID storageLocationId, UUID storageBinId,
        BigDecimal countedQuantity, String reason, List<UUID> evidenceIds) {}
    public record Valuation(Long expectedLineVersion, Long countRevision, BigDecimal unitCost,
        String reason, List<UUID> evidenceIds) {}
}
