package com.enterprise.mm.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PoReceivingContextResponse(
    String receivingMode,
    PoReceivingHeader header,
    List<PoItemLine> lines,
    AccreditationInfo accreditation,
    List<AsnSummary> advanceShippingNotices,
    List<EvidenceSummary> evidence,
    List<ReceiptSummary> receipts,
    List<StockMovementSummary> stockMovements,
    List<AuditTimelineSummary> auditTimeline,
    List<IntegrationImpactSummary> integrationImpact,
    List<WorkspaceBlocker> blockers,
    List<WorkspaceAction> allowedActions,
    GoodsReceiptContext goodsReceipt,
    AssetAcceptanceContext assetAcceptance,
    ServiceAcceptanceContext serviceAcceptance,
    LocalDateTime generatedAt
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PoReceivingHeader(
        UUID id,
        String code,
        String displayName,
        String status,
        Long version,
        SupplierSummary supplier,
        LocalDate orderDate,
        LocalDate expectedDeliveryDate,
        String requestType,
        BigDecimal totalAmount,
        String currencyCode
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SupplierSummary(
        UUID id,
        String code,
        String displayName,
        String status
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PoItemLine(
        UUID id,
        String code,
        String displayName,
        MaterialSummary material,
        MaterialGroupSummary materialGroup,
        String unit,
        String trackingModeSnapshot,
        String batchTrackingSnapshot,
        String serialTrackingSnapshot,
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
        Boolean accreditationEligible,
        String accreditationMessage,
        BigDecimal previouslyReceivedQuantity,
        BigDecimal shortageQuantity,
        BigDecimal excessQuantity,
        BigDecimal acceptedShortfallQuantity,
        String unitShortName
    ) {
        public PoItemLine(UUID id,
        String code,
        String displayName,
        MaterialSummary material,
        MaterialGroupSummary materialGroup,
        String unit,
        String trackingModeSnapshot,
        String batchTrackingSnapshot,
        String serialTrackingSnapshot,
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
        Boolean accreditationEligible,
        String accreditationMessage,
        BigDecimal previouslyReceivedQuantity,
        BigDecimal shortageQuantity,
        BigDecimal excessQuantity,
        BigDecimal acceptedShortfallQuantity) {
            this(id, code, displayName, material, materialGroup, unit, trackingModeSnapshot, batchTrackingSnapshot, serialTrackingSnapshot, orderedQuantity, acceptedQuantity, rejectedQuantity, damagedQuantity, processedQuantity, openQuantity, shortageReason, batchNumber, receiptWarehouseId, receiptStorageLocationId, qualityStatus, lineStatus, accreditationEligible, accreditationMessage, previouslyReceivedQuantity, shortageQuantity, excessQuantity, acceptedShortfallQuantity, null);
        }
        public PoItemLine withUnitShortName(String value) {
            return new PoItemLine(id, code, displayName, material, materialGroup, unit, trackingModeSnapshot, batchTrackingSnapshot, serialTrackingSnapshot, orderedQuantity, acceptedQuantity, rejectedQuantity, damagedQuantity, processedQuantity, openQuantity, shortageReason, batchNumber, receiptWarehouseId, receiptStorageLocationId, qualityStatus, lineStatus, accreditationEligible, accreditationMessage, previouslyReceivedQuantity, shortageQuantity, excessQuantity, acceptedShortfallQuantity, value);
        }
}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MaterialSummary(
        UUID id,
        String code,
        String displayName,
        String status
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MaterialGroupSummary(
        UUID id,
        String code,
        String displayName,
        String status
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AccreditationInfo(
        boolean eligible,
        String status,
        String blockedReason,
        UUID applicationId,
        String applicationCode,
        String approvedScope,
        List<MaterialGroupSummary> materialGroups,
        LocalDate validTo,
        String conditions,
        String overallStatus
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AsnSummary(
        UUID id,
        String code,
        String displayName,
        String status,
        LocalDate expectedArrivalDate,
        String carrierName,
        String vehicleNumber,
        boolean receiptEligible,
        String receiptBlockedReason,
        List<String> expectedBatches,
        List<String> expectedSerials
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EvidenceSummary(
        UUID id,
        String name,
        String fileKey,
        String documentType,
        LocalDateTime uploadedAt
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReceiptSummary(
        UUID id,
        String code,
        LocalDateTime postedAt,
        String postedBy,
        List<ReceiptLineSummary> lines
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReceiptLineSummary(
        UUID materialId,
        String materialName,
        String unit,
        BigDecimal acceptedQuantity,
        BigDecimal rejectedQuantity,
        BigDecimal damagedQuantity,
        String qualityStatus,
        String unitShortName
    ) {
        public ReceiptLineSummary(UUID materialId,
        String materialName,
        String unit,
        BigDecimal acceptedQuantity,
        BigDecimal rejectedQuantity,
        BigDecimal damagedQuantity,
        String qualityStatus) {
            this(materialId, materialName, unit, acceptedQuantity, rejectedQuantity, damagedQuantity, qualityStatus, null);
        }
        public ReceiptLineSummary withUnitShortName(String value) {
            return new ReceiptLineSummary(materialId, materialName, unit, acceptedQuantity, rejectedQuantity, damagedQuantity, qualityStatus, value);
        }
}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StockMovementSummary(
        UUID id,
        String movementType,
        BigDecimal quantity,
        String unit,
        LocalDate movementDate,
        String unitShortName
    ) {
        public StockMovementSummary(UUID id,
        String movementType,
        BigDecimal quantity,
        String unit,
        LocalDate movementDate) {
            this(id, movementType, quantity, unit, movementDate, null);
        }
        public StockMovementSummary withUnitShortName(String value) {
            return new StockMovementSummary(id, movementType, quantity, unit, movementDate, value);
        }
}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AuditTimelineSummary(
        UUID id,
        String action,
        String actor,
        String details,
        LocalDateTime occurredAt
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IntegrationImpactSummary(
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
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkspaceBlocker(
        String code,
        String severity,
        String message,
        String resolutionAction
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
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
    ) {}

    // MODE CONTEXTS
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GoodsReceiptContext(
        boolean readyForReceiving
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AssetAcceptanceContext(
        List<AcceptanceSummary> acceptances,
        AcceptanceSummary activeAcceptance,
        AssetSettings settings
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AcceptanceSummary(
        UUID id,
        String approvalStatus,
        String postingStatus,
        UUID warehouseId,
        String warehouseName,
        UUID asnId,
        String asnNumber,
        LocalDate receiptDate,
        LocalDateTime receivedAt,
        String sourceEvidence,
        String deliveryReference,
        LocalDateTime postedAt,
        String postedBy,
        List<AcceptanceLineSummary> lines
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AcceptanceLineSummary(
        UUID id,
        UUID purchaseOrderItemId,
        UUID materialId,
        String materialName,
        String unit,
        BigDecimal orderedQuantity,
        BigDecimal deliveredQuantity,
        BigDecimal acceptedQuantity,
        BigDecimal rejectedQuantity,
        BigDecimal pendingQuantity,
        BigDecimal holdQuantity,
        String qualityDecision,
        BigDecimal acceptedValue,
        String acceptedCurrencyCode,
        List<AcceptanceUnitSummary> units,
        String unitShortName
    ) {
        public AcceptanceLineSummary(UUID id,
        UUID purchaseOrderItemId,
        UUID materialId,
        String materialName,
        String unit,
        BigDecimal orderedQuantity,
        BigDecimal deliveredQuantity,
        BigDecimal acceptedQuantity,
        BigDecimal rejectedQuantity,
        BigDecimal pendingQuantity,
        BigDecimal holdQuantity,
        String qualityDecision,
        BigDecimal acceptedValue,
        String acceptedCurrencyCode,
        List<AcceptanceUnitSummary> units) {
            this(id, purchaseOrderItemId, materialId, materialName, unit, orderedQuantity, deliveredQuantity, acceptedQuantity, rejectedQuantity, pendingQuantity, holdQuantity, qualityDecision, acceptedValue, acceptedCurrencyCode, units, null);
        }
        public AcceptanceLineSummary withUnitShortName(String value) {
            return new AcceptanceLineSummary(id, purchaseOrderItemId, materialId, materialName, unit, orderedQuantity, deliveredQuantity, acceptedQuantity, rejectedQuantity, pendingQuantity, holdQuantity, qualityDecision, acceptedValue, acceptedCurrencyCode, units, value);
        }
}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AcceptanceUnitSummary(
        UUID id,
        String receivingUnitNumber,
        String manufacturerSerialNumber,
        String condition,
        String qualityDecision,
        LocalDateTime qualityDecisionAt,
        String qualityDecisionBy,
        UUID postedFixedAssetId,
        String postedFixedAssetName
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AssetSettings(
        UUID centralWarehouseId,
        String centralWarehouseName,
        boolean requireSerialNumber,
        boolean autoNumberAtReceipt,
        String inventoryNumberPrefix,
        boolean featureEnabled,
        boolean asnRequired
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ServiceAcceptanceContext(
        List<ServiceActSummary> acts,
        ServiceActSummary activeAct,
        ServiceAmountSummary summary
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ServiceActSummary(
        UUID id,
        String code,
        String name,
        String status,
        UUID serviceOrderId,
        String serviceOrderCode,
        UUID contractId,
        String contractCode,
        BigDecimal amount,
        BigDecimal vatAmount,
        BigDecimal vatRate,
        LocalDate periodStart,
        LocalDate periodEnd,
        String stage,
        LocalDateTime acceptedAt,
        String acceptedBy,
        LocalDateTime documentReviewedAt,
        LocalDateTime financeReviewedAt,
        LocalDateTime sentToPaymentAt,
        LocalDateTime paidAt,
        String paymentReference,
        String commissionNotes,
        String remarks
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ServiceAmountSummary(
        BigDecimal orderedAmount,
        BigDecimal acceptedAmount,
        BigDecimal remainingAmount,
        String currencyCode
    ) {}
}
