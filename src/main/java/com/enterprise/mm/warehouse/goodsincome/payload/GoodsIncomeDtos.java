package com.enterprise.mm.warehouse.goodsincome.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.enterprise.mm.enums.BatchTrackingPolicy;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.enums.TrackingMode;

public final class GoodsIncomeDtos {
    private GoodsIncomeDtos() {
    }

    public record GoodsIncomeQueueFilter(
        String search,
        List<String> status,
        UUID supplierId,
        UUID contractId,
        Boolean overdue
    ) {
        public GoodsIncomeQueueFilter(String search, List<String> status, UUID supplierId, Boolean overdue) {
            this(search, status, supplierId, null, overdue);
        }

        public GoodsIncomeQueueFilter normalized() {
            String normalizedSearch = search == null || search.isBlank()
                ? null
                : search.trim().toLowerCase(Locale.ROOT);
            List<String> normalizedStatus = status == null
                ? List.of()
                : status.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toUpperCase(Locale.ROOT))
                    .distinct()
                    .sorted()
                    .toList();
            return new GoodsIncomeQueueFilter(normalizedSearch, normalizedStatus, supplierId, contractId, overdue);
        }
    }

    public record EntityRef(UUID id, String code, String displayName, String status) {
    }

    public record GoodsIncomeQueueItem(
        EntityRef purchaseOrder,
        EntityRef supplier,
        EntityRef contract,
        String statusLabelKey,
        LocalDate expectedDeliveryDate,
        boolean overdue,
        BigDecimal totalAmount,
        String currencyCode,
        long openLineCount,
        BigDecimal openQuantity,
        String unit,
        String openQuantityUom,
        boolean mixedUom,
        String nextAction,
        String blockerCode,
        String blockerMessage,
        String ownerRole,
        LocalDate dueDate,
        Long version,
        String unitShortName
,
        String openQuantityUomShortName
    ) {
        public GoodsIncomeQueueItem(EntityRef purchaseOrder,
        EntityRef supplier,
        EntityRef contract,
        String statusLabelKey,
        LocalDate expectedDeliveryDate,
        boolean overdue,
        BigDecimal totalAmount,
        String currencyCode,
        long openLineCount,
        BigDecimal openQuantity,
        String unit,
        String openQuantityUom,
        boolean mixedUom,
        String nextAction,
        String blockerCode,
        String blockerMessage,
        String ownerRole,
        LocalDate dueDate,
        Long version,
        String unitShortName) {
            this(purchaseOrder, supplier, contract, statusLabelKey, expectedDeliveryDate, overdue, totalAmount, currencyCode, openLineCount, openQuantity, unit, openQuantityUom, mixedUom, nextAction, blockerCode, blockerMessage, ownerRole, dueDate, version, unitShortName, null);
        }
        public GoodsIncomeQueueItem withOpenQuantityUomShortName(String value) {
            return new GoodsIncomeQueueItem(purchaseOrder, supplier, contract, statusLabelKey, expectedDeliveryDate, overdue, totalAmount, currencyCode, openLineCount, openQuantity, unit, openQuantityUom, mixedUom, nextAction, blockerCode, blockerMessage, ownerRole, dueDate, version, unitShortName, value);
        }

        public GoodsIncomeQueueItem(EntityRef purchaseOrder,
        EntityRef supplier,
        EntityRef contract,
        String statusLabelKey,
        LocalDate expectedDeliveryDate,
        boolean overdue,
        BigDecimal totalAmount,
        String currencyCode,
        long openLineCount,
        BigDecimal openQuantity,
        String unit,
        String openQuantityUom,
        boolean mixedUom,
        String nextAction,
        String blockerCode,
        String blockerMessage,
        String ownerRole,
        LocalDate dueDate,
        Long version) {
            this(purchaseOrder, supplier, contract, statusLabelKey, expectedDeliveryDate, overdue, totalAmount, currencyCode, openLineCount, openQuantity, unit, openQuantityUom, mixedUom, nextAction, blockerCode, blockerMessage, ownerRole, dueDate, version, null);
        }
        public GoodsIncomeQueueItem withUnitShortName(String value) {
            return new GoodsIncomeQueueItem(purchaseOrder, supplier, contract, statusLabelKey, expectedDeliveryDate, overdue, totalAmount, currencyCode, openLineCount, openQuantity, unit, openQuantityUom, mixedUom, nextAction, blockerCode, blockerMessage, ownerRole, dueDate, version, value, openQuantityUomShortName);
        }

    }

    public record GoodsIncomeQueueResponse(
        String queueKey,
        String titleKey,
        GoodsIncomeQueueFilter appliedFilters,
        long totalElements,
        List<GoodsIncomeQueueItem> items,
        int page,
        int size,
        int totalPages,
        LocalDateTime generatedAt,
        String dataScope
    ) {
    }

    public record GoodsIncomeHeader(
        UUID id,
        String code,
        String displayName,
        String status,
        Long version,
        EntityRef supplier,
        EntityRef contract,
        LocalDate orderDate,
        LocalDate expectedDeliveryDate,
        String requestType,
        BigDecimal totalAmount,
        String currencyCode
    ) {
        public GoodsIncomeHeader(
            UUID id,
            String code,
            String displayName,
            String status,
            Long version,
            EntityRef supplier,
            LocalDate orderDate,
            LocalDate expectedDeliveryDate,
            String requestType,
            BigDecimal totalAmount,
            String currencyCode
        ) {
            this(id, code, displayName, status, version, supplier, null, orderDate,
                expectedDeliveryDate, requestType, totalAmount, currencyCode);
        }
    }

    public record GoodsIncomeLine(
        UUID id,
        String code,
        String displayName,
        EntityRef material,
        EntityRef materialGroup,
        String unit,
        TrackingMode trackingModeSnapshot,
        BatchTrackingPolicy batchTrackingSnapshot,
        SerialTrackingPolicy serialTrackingSnapshot,
        BigDecimal orderedQuantity,
        BigDecimal acceptedQuantity,
        BigDecimal rejectedQuantity,
        BigDecimal damagedQuantity,
        BigDecimal processedQuantity,
        BigDecimal openQuantity,
        String shortageReason,
        String batchNumber,
        UUID receiptWarehouseId,
        UUID receiptStorageLocationId,
        String qualityStatus,
        String lineStatus,
        boolean accreditationEligible,
        String accreditationMessage,
        BigDecimal previouslyReceivedQuantity,
        BigDecimal shortageQuantity,
        BigDecimal excessQuantity,
        String unitShortName
    ) {
        public GoodsIncomeLine(UUID id,
        String code,
        String displayName,
        EntityRef material,
        EntityRef materialGroup,
        String unit,
        TrackingMode trackingModeSnapshot,
        BatchTrackingPolicy batchTrackingSnapshot,
        SerialTrackingPolicy serialTrackingSnapshot,
        BigDecimal orderedQuantity,
        BigDecimal acceptedQuantity,
        BigDecimal rejectedQuantity,
        BigDecimal damagedQuantity,
        BigDecimal processedQuantity,
        BigDecimal openQuantity,
        String shortageReason,
        String batchNumber,
        UUID receiptWarehouseId,
        UUID receiptStorageLocationId,
        String qualityStatus,
        String lineStatus,
        boolean accreditationEligible,
        String accreditationMessage,
        BigDecimal previouslyReceivedQuantity,
        BigDecimal shortageQuantity,
        BigDecimal excessQuantity) {
            this(id, code, displayName, material, materialGroup, unit, trackingModeSnapshot, batchTrackingSnapshot, serialTrackingSnapshot, orderedQuantity, acceptedQuantity, rejectedQuantity, damagedQuantity, processedQuantity, openQuantity, shortageReason, batchNumber, receiptWarehouseId, receiptStorageLocationId, qualityStatus, lineStatus, accreditationEligible, accreditationMessage, previouslyReceivedQuantity, shortageQuantity, excessQuantity, null);
        }
        public GoodsIncomeLine withUnitShortName(String value) {
            return new GoodsIncomeLine(id, code, displayName, material, materialGroup, unit, trackingModeSnapshot, batchTrackingSnapshot, serialTrackingSnapshot, orderedQuantity, acceptedQuantity, rejectedQuantity, damagedQuantity, processedQuantity, openQuantity, shortageReason, batchNumber, receiptWarehouseId, receiptStorageLocationId, qualityStatus, lineStatus, accreditationEligible, accreditationMessage, previouslyReceivedQuantity, shortageQuantity, excessQuantity, value);
        }

        @com.fasterxml.jackson.annotation.JsonProperty("acceptedShortfallQuantity")
        public BigDecimal acceptedShortfallQuantity() {
            return orderedQuantity.subtract(acceptedQuantity).max(BigDecimal.ZERO);
        }

        public GoodsIncomeLine(
            UUID id,
            String code,
            String displayName,
            EntityRef material,
            EntityRef materialGroup,
            String unit,
            BigDecimal orderedQuantity,
            BigDecimal acceptedQuantity,
            BigDecimal rejectedQuantity,
            BigDecimal damagedQuantity,
            BigDecimal processedQuantity,
            BigDecimal openQuantity,
            String shortageReason,
            String batchNumber,
            UUID receiptWarehouseId,
            UUID receiptStorageLocationId,
            String qualityStatus,
            String lineStatus,
            boolean accreditationEligible,
            String accreditationMessage,
            BigDecimal previouslyReceivedQuantity,
            BigDecimal shortageQuantity,
            BigDecimal excessQuantity
        ) {
            this(
                id, code, displayName, material, materialGroup, unit,
                TrackingMode.QUANTITY, BatchTrackingPolicy.NONE, SerialTrackingPolicy.NONE,
                orderedQuantity, acceptedQuantity, rejectedQuantity, damagedQuantity, processedQuantity, openQuantity,
                shortageReason, batchNumber, receiptWarehouseId, receiptStorageLocationId, qualityStatus, lineStatus,
                accreditationEligible, accreditationMessage, previouslyReceivedQuantity, shortageQuantity, excessQuantity
            );
        }
    }

    public record AdvanceShippingNoticeContext(
        UUID id,
        String code,
        String displayName,
        String status,
        LocalDate expectedArrivalDate,
        String carrierName,
        String vehicleNumber,
        boolean receiptEligible,
        String receiptBlockedReason,
        List<ExpectedAsnBatch> expectedBatches,
        List<ExpectedAsnSerial> expectedSerials
    ) {
        public AdvanceShippingNoticeContext(
            UUID id,
            String code,
            String displayName,
            String status,
            LocalDate expectedArrivalDate,
            String carrierName,
            String vehicleNumber,
            boolean receiptEligible,
            String receiptBlockedReason
        ) {
            this(
                id, code, displayName, status, expectedArrivalDate, carrierName, vehicleNumber,
                receiptEligible, receiptBlockedReason, List.of(), List.of()
            );
        }
    }

    public record ExpectedAsnBatch(
        UUID asnItemId,
        String batchNumber,
        BigDecimal quantity,
        LocalDate expirationDate
    ) {
    }

    public record ExpectedAsnSerial(
        UUID asnItemId,
        String manufacturerSerialNumber,
        String batchNumber
    ) {
    }

    public record AccreditationContext(
        boolean eligible,
        String status,
        String blockedReason,
        UUID applicationId,
        String applicationCode,
        String approvedScope,
        List<EntityRef> materialGroups,
        LocalDate validTo,
        String conditions,
        String overallStatus
    ) {
    }

    public record WorkspaceBlocker(
        String code,
        String severity,
        String message,
        String ownerRole,
        String resolutionAction
    ) {
    }

    public record WorkspaceAction(
        String code,
        String labelKey,
        boolean enabled,
        String disabledReason,
        List<String> requiredRoles,
        List<String> requiredFields,
        List<String> requiredEvidence,
        String confirmationImpact,
        boolean irreversible,
        boolean idempotencyRequired
    ) {
    }

    public record EvidenceItem(
        UUID id,
        String displayName,
        String attachmentType,
        String contentType,
        LocalDateTime uploadedAt,
        boolean previewable,
        boolean downloadable
    ) {
    }

    public record StockMovementContext(
        UUID id,
        String code,
        String movementType,
        String status,
        UUID materialId,
        UUID warehouseId,
        UUID storageLocationId,
        UUID storageBinId,
        BigDecimal quantity,
        String batchNumber,
        LocalDate movementDate,
        LocalDateTime postedAt
    ) {
    }

    public record ReceiptLineContext(
        UUID id,
        UUID purchaseOrderItemId,
        UUID materialId,
        String uomCode,
        UUID storageLocationId,
        UUID storageBinId,
        String batchNumber,
        BigDecimal openQuantityBefore,
        BigDecimal acceptedQuantity,
        BigDecimal rejectedQuantity,
        BigDecimal damagedQuantity,
        BigDecimal shortageQuantity,
        BigDecimal excessQuantity,
        String shortageReason,
        String qualityStatus,
        UUID stockMovementId,
        String status,
        String unitShortName
    ) {
        public ReceiptLineContext(UUID id,
        UUID purchaseOrderItemId,
        UUID materialId,
        String uomCode,
        UUID storageLocationId,
        UUID storageBinId,
        String batchNumber,
        BigDecimal openQuantityBefore,
        BigDecimal acceptedQuantity,
        BigDecimal rejectedQuantity,
        BigDecimal damagedQuantity,
        BigDecimal shortageQuantity,
        BigDecimal excessQuantity,
        String shortageReason,
        String qualityStatus,
        UUID stockMovementId,
        String status) {
            this(id, purchaseOrderItemId, materialId, uomCode, storageLocationId, storageBinId, batchNumber, openQuantityBefore, acceptedQuantity, rejectedQuantity, damagedQuantity, shortageQuantity, excessQuantity, shortageReason, qualityStatus, stockMovementId, status, null);
        }
        public ReceiptLineContext withUnitShortName(String value) {
            return new ReceiptLineContext(id, purchaseOrderItemId, materialId, uomCode, storageLocationId, storageBinId, batchNumber, openQuantityBefore, acceptedQuantity, rejectedQuantity, damagedQuantity, shortageQuantity, excessQuantity, shortageReason, qualityStatus, stockMovementId, status, value);
        }

    }

    public record ReceiptContext(
        UUID id,
        String code,
        String status,
        UUID warehouseId,
        BigDecimal acceptedQuantity,
        BigDecimal rejectedQuantity,
        BigDecimal damagedQuantity,
        BigDecimal shortageQuantity,
        BigDecimal acceptedAmount,
        String currencyCode,
        BigDecimal baseAcceptedAmount,
        String baseCurrencyCode,
        BigDecimal exchangeRate,
        LocalDateTime postedAt,
        String postedBy,
        List<ReceiptLineContext> lines
    ) {
    }

    public record AuditTimelineItem(
        UUID id,
        String action,
        String actor,
        String details,
        LocalDateTime occurredAt
    ) {
    }

    public record IntegrationImpactItem(
        UUID id,
        String code,
        String eventType,
        String status,
        Integer retryCount,
        String lastError,
        LocalDateTime createdAt,
        String businessDocumentType,
        String businessDocumentCode,
        boolean identityAvailable,
        String recoveryStatus
    ) {
    }

    public record GoodsIncomeContext(
        GoodsIncomeHeader header,
        List<GoodsIncomeLine> lines,
        List<AdvanceShippingNoticeContext> advanceShippingNotices,
        AccreditationContext accreditation,
        List<WorkspaceBlocker> blockers,
        List<WorkspaceAction> allowedActions,
        List<EvidenceItem> evidence,
        List<ReceiptContext> receipts,
        List<StockMovementContext> stockMovements,
        List<AuditTimelineItem> auditTimeline,
        List<IntegrationImpactItem> integrationImpact,
        LocalDateTime generatedAt,
        String dataScope
    ) {
    }
}
