package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class BusinessActionDtos {
    private BusinessActionDtos() {
    }

    public record ApprovalDecision(String comment) {
    }

    public record ConvertToPoRequest(UUID quotationId) {
    }

    public record SelectQuotationRequest(String reason) {
    }

    public record TenderBidOpeningRequest(String openedBy, Integer quorumPresent, String notes) {
    }

    public record TenderEvaluationProtocolRequest(UUID recommendedQuotationId, String reason, String approvedBy, String scoringSummary) {
    }

    public record TenderConflictResolutionRequest(String mitigationAction) {
    }

    public record ReceivePoRequest(
        UUID warehouseId,
        UUID storageLocationId,
        UUID asnId,
        List<ReceivePoLineRequest> lines,
        UUID storageBinId,
        Long version,
        List<UUID> evidenceAttachmentIds,
        UUID fakturaPowerOfAttorneyId,
        UUID fakturaInvoiceId,
        String missingFakturaReason,
        List<com.enterprise.mm.warehouse.packaging.PackagingReceiptRequest> packaging
    ) {
        public ReceivePoRequest(UUID warehouseId, UUID storageLocationId, UUID asnId,
            List<ReceivePoLineRequest> lines, UUID storageBinId, Long version,
            List<UUID> evidenceAttachmentIds, UUID fakturaPowerOfAttorneyId,
            UUID fakturaInvoiceId, String missingFakturaReason) {
            this(warehouseId, storageLocationId, asnId, lines, storageBinId, version,
                evidenceAttachmentIds, fakturaPowerOfAttorneyId, fakturaInvoiceId, missingFakturaReason, List.of());
        }
        public ReceivePoRequest(UUID warehouseId, UUID storageLocationId, UUID asnId,
            List<ReceivePoLineRequest> lines, UUID storageBinId, Long version,
            List<UUID> evidenceAttachmentIds) {
            this(warehouseId, storageLocationId, asnId, lines, storageBinId, version,
                evidenceAttachmentIds, null, null, null);
        }
        public ReceivePoRequest(UUID warehouseId, UUID storageLocationId, UUID asnId, List<ReceivePoLineRequest> lines) {
            this(warehouseId, storageLocationId, asnId, lines, null, null, List.of());
        }

        public ReceivePoRequest(
            UUID warehouseId,
            UUID storageLocationId,
            UUID asnId,
            List<ReceivePoLineRequest> lines,
            UUID storageBinId
        ) {
            this(warehouseId, storageLocationId, asnId, lines, storageBinId, null, List.of());
        }
    }

    public record ReceivePoLineRequest(
        UUID purchaseOrderItemId,
        BigDecimal receivedQuantity,
        BigDecimal rejectedQuantity,
        BigDecimal damagedQuantity,
        String shortageReason,
        String batchNumber,
        UUID storageLocationId,
        String qualityStatus,
        UUID storageBinId,
        BigDecimal acceptedQuantity,
        List<ReceivePoUnitRequest> units,
        List<ReceivePoBatchRequest> batches
    ) {
        public ReceivePoLineRequest {
            units = units == null ? List.of() : List.copyOf(units);
            batches = batches == null ? List.of() : List.copyOf(batches);
        }

        public ReceivePoLineRequest(
            UUID purchaseOrderItemId,
            BigDecimal receivedQuantity,
            BigDecimal rejectedQuantity,
            BigDecimal damagedQuantity,
            String shortageReason,
            String batchNumber,
            UUID storageLocationId,
            String qualityStatus
        ) {
            this(purchaseOrderItemId, receivedQuantity, rejectedQuantity, damagedQuantity, shortageReason, batchNumber, storageLocationId, qualityStatus, null, null, List.of(), List.of());
        }

        public ReceivePoLineRequest(
            UUID purchaseOrderItemId,
            BigDecimal receivedQuantity,
            BigDecimal rejectedQuantity,
            BigDecimal damagedQuantity,
            String shortageReason,
            String batchNumber,
            UUID storageLocationId,
            String qualityStatus,
            UUID storageBinId
        ) {
            this(purchaseOrderItemId, receivedQuantity, rejectedQuantity, damagedQuantity, shortageReason, batchNumber, storageLocationId, qualityStatus, storageBinId, null, List.of(), List.of());
        }

        public ReceivePoLineRequest(
            UUID purchaseOrderItemId,
            BigDecimal receivedQuantity,
            BigDecimal rejectedQuantity,
            BigDecimal damagedQuantity,
            String shortageReason,
            String batchNumber,
            UUID storageLocationId,
            String qualityStatus,
            UUID storageBinId,
            BigDecimal acceptedQuantity
        ) {
            this(purchaseOrderItemId, receivedQuantity, rejectedQuantity, damagedQuantity, shortageReason, batchNumber, storageLocationId, qualityStatus, storageBinId, acceptedQuantity, List.of(), List.of());
        }

        public ReceivePoLineRequest(
            UUID purchaseOrderItemId,
            BigDecimal receivedQuantity,
            BigDecimal rejectedQuantity,
            BigDecimal damagedQuantity,
            String shortageReason,
            String batchNumber,
            UUID storageLocationId,
            String qualityStatus,
            UUID storageBinId,
            BigDecimal acceptedQuantity,
            List<ReceivePoUnitRequest> units
        ) {
            this(purchaseOrderItemId, receivedQuantity, rejectedQuantity, damagedQuantity, shortageReason, batchNumber, storageLocationId, qualityStatus, storageBinId, acceptedQuantity, units, List.of());
        }
    }

    public record ReceivePoBatchRequest(
        String batchKey,
        String manufacturerBatchNumber,
        LocalDate manufacturedOn,
        LocalDate expiresOn,
        BigDecimal quantity,
        List<ReceivePoUnitRequest> units
    ) {
        public ReceivePoBatchRequest {
            units = units == null ? List.of() : List.copyOf(units);
        }
    }

    public record ReceivePoUnitRequest(
        String manufacturerSerialNumber,
        String condition,
        String qualityDecision,
        String notes
    ) {
    }

    public record ReceiptMovementOutcome(
        UUID stockMovementId,
        UUID purchaseOrderItemId,
        UUID materialId,
        BigDecimal acceptedQuantity,
        LocalDate movementDate
    ) {
    }

    public record ReceiptIntegrationOutcome(UUID eventId, String eventType, String status) {
    }

    public record ReceiptQualityOutcome(
        UUID qualityInspectionId,
        String inspectionNumber,
        UUID purchaseOrderItemId,
        UUID stockMovementId,
        BigDecimal quantity,
        String status
    ) {
    }

    public record ReceiptOutcome(
        UUID purchaseOrderId,
        String purchaseOrderStatus,
        BigDecimal acceptedQuantity,
        BigDecimal rejectedQuantity,
        BigDecimal damagedQuantity,
        BigDecimal notAvailableQuantity,
        List<ReceiptMovementOutcome> stockMovements,
        List<ReceiptIntegrationOutcome> integrationEvents,
        List<ReceiptQualityOutcome> qualityInspections,
        UUID goodsReceiptId,
        String goodsReceiptCode,
        Integer materialUnitsCreated,
        Integer receiptUnitsRecorded,
        LocalDateTime completedAt
    ) {
    }

    public record SupplierEvaluationRequest(
        UUID supplierId,
        UUID purchaseOrderId,
        BigDecimal deliveryScore,
        BigDecimal qualityScore,
        BigDecimal quantityScore,
        BigDecimal priceScore,
        BigDecimal claimsScore
    ) {
    }

    public record NonMovingStockActionRequest(String action, String comment) {
    }

    public record ServiceAcceptRequest(String acceptedBy, String commissionNotes) {
    }

    public record ServicePaymentRequest(String paymentReference, String comment) {
    }
}
