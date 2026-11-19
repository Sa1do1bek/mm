package com.enterprise.mm.payload;

import com.enterprise.mm.enums.BatchTrackingPolicy;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.enums.TrackingMode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Portal-safe DTOs for the supplier PO collaboration public endpoints (mirrors
 * {@link SupplierPortalPublicDtos}'s convention of a single payload holder class per
 * portal domain area). Deliberately does NOT reuse the internal
 * {@code PurchaseOrderResponse} -- these types must never carry internal costs,
 * approval comments, budget details, warehouse-only data, or other-supplier data.
 * Field names match the frontend contract given in docs/CURRENT_TASK.md exactly.
 */
public final class SupplierPortalPurchaseOrderDtos {
    private SupplierPortalPurchaseOrderDtos() {
    }

    public record SupplierPortalPurchaseOrderSummary(
        UUID id,
        String poNumber,
        Long issueVersion,
        String buyerName,
        BigDecimal amount,
        String currency,
        LocalDate requestedDate,
        LocalDate responseDeadline,
        String dispatchState,
        String acknowledgementStatus,
        String fulfillmentStatus,
        boolean overdue,
        boolean actionRequired,
        boolean alreadyResponded,
        boolean legacy
    ) {
    }

    public record SupplierPortalPurchaseOrderLine(
        UUID purchaseOrderItemId,
        UUID materialId,
        String materialCode,
        String materialName,
        TrackingMode trackingModeSnapshot,
        BatchTrackingPolicy batchTrackingSnapshot,
        SerialTrackingPolicy serialTrackingSnapshot,
        String unit,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal openQuantity,
        boolean shippable,
        boolean shippableAsGoods
    ) {
    }

    public record SupplierPortalAttachmentView(
        UUID id,
        String fileName,
        String contentType,
        Long sizeBytes
    ) {
    }

    public record SupplierPortalPurchaseOrderDetail(
        UUID id,
        String poNumber,
        String poName,
        Long issueVersion,
        String buyerName,
        BigDecimal amount,
        String currency,
        LocalDate orderDate,
        LocalDate expectedDeliveryDate,
        LocalDate responseDeadline,
        UUID dispatchId,
        String dispatchStatus,
        boolean legacy,
        boolean alreadyResponded,
        String fulfillmentMode,
        String completionStatus,
        String buyerReviewStatus,
        BigDecimal acceptedQuantity,
        BigDecimal acceptedAmount,
        BigDecimal remainingAcceptanceQuantity,
        BigDecimal remainingAcceptanceAmount,
        UUID acceptanceActId,
        String acceptanceActNumber,
        boolean completionAttachmentRequired,
        List<String> allowedActions,
        List<SupplierPortalPurchaseOrderLine> lines,
        List<SupplierPortalAttachmentView> artifacts,
        SupplierPortalResponseView currentResponse,
        SupplierPortalCompletionView currentCompletion,
        List<SupplierPortalCompletionView> completions,
        List<SupplierPortalAsnView> asns
    ) {
    }

    public record SupplierPortalCommitmentCommand(
        BigDecimal proposedQuantity,
        LocalDate proposedDate
    ) {
    }

    public record SupplierPortalResponseLineRequest(
        UUID purchaseOrderItemId,
        String decision,
        BigDecimal acceptedQuantity,
        String reasonCode,
        String supplierComment,
        List<SupplierPortalCommitmentCommand> commitments
    ) {
    }

    public record SupplierPortalResponseDraftRequest(
        String supplierComment,
        List<SupplierPortalResponseLineRequest> lines
    ) {
    }

    public record SupplierPortalCommitmentView(
        UUID id,
        UUID purchaseOrderItemId,
        UUID materialId,
        String materialCode,
        String materialName,
        TrackingMode trackingModeSnapshot,
        BatchTrackingPolicy batchTrackingSnapshot,
        SerialTrackingPolicy serialTrackingSnapshot,
        BigDecimal proposedQuantity,
        LocalDate proposedDate,
        BigDecimal confirmedQuantity,
        LocalDate confirmedDate,
        String status,
        BigDecimal remainingQuantity,
        boolean shippable,
        boolean shippableAsGoods
    ) {
    }

    public record SupplierPortalResponseLineView(
        UUID id,
        UUID purchaseOrderItemId,
        String decision,
        BigDecimal orderedQuantity,
        BigDecimal openQuantityAtDispatch,
        BigDecimal acceptedQuantity,
        BigDecimal unconfirmedQuantity,
        String reasonCode,
        String supplierComment,
        List<SupplierPortalCommitmentView> commitments
    ) {
    }

    public record SupplierPortalResponseView(
        UUID id,
        int responseRevision,
        String responseType,
        String responseStatus,
        String supplierComment,
        String rejectionReason,
        LocalDateTime submittedAt,
        LocalDateTime buyerDecisionAt,
        String buyerDecisionComment,
        List<SupplierPortalResponseLineView> lines
    ) {
    }

    public record SupplierPortalAsnBatchRequest(
        String batchNumber,
        BigDecimal quantity,
        LocalDate expirationDate
    ) {
    }

    public record SupplierPortalAsnSerialRequest(
        String manufacturerSerialNumber,
        String batchNumber
    ) {
    }

    public record SupplierPortalAsnLineRequest(
        UUID supplierPoCommitmentId,
        BigDecimal quantity,
        BigDecimal packageCount,
        String packageType,
        BigDecimal grossWeight,
        BigDecimal netWeight,
        String batchNumber,
        List<String> serialNumbers,
        LocalDate expirationDate,
        List<SupplierPortalAsnBatchRequest> batches,
        List<SupplierPortalAsnSerialRequest> serials
    ) {
        public SupplierPortalAsnLineRequest(UUID supplierPoCommitmentId, BigDecimal quantity, String batchNumber, LocalDate expirationDate) {
            this(supplierPoCommitmentId, quantity, null, null, null, null, batchNumber, null, expirationDate, List.of(), List.of());
        }

        public SupplierPortalAsnLineRequest(
            UUID supplierPoCommitmentId,
            BigDecimal quantity,
            BigDecimal packageCount,
            String packageType,
            BigDecimal grossWeight,
            BigDecimal netWeight,
            String batchNumber,
            List<String> serialNumbers,
            LocalDate expirationDate
        ) {
            this(
                supplierPoCommitmentId, quantity, packageCount, packageType, grossWeight, netWeight,
                batchNumber, serialNumbers, expirationDate, List.of(), List.of()
            );
        }
    }

    public record SupplierPortalAsnDraftRequest(
        String asnNumber,
        LocalDate noticeDate,
        LocalDate expectedShipDate,
        LocalDate expectedArrivalDate,
        String transportMode,
        String carrierName,
        String vehicleNumber,
        String containerNumber,
        String trackingNumber,
        List<SupplierPortalAsnLineRequest> lines
    ) {
    }

    public record SupplierPortalAsnBatchView(
        UUID id,
        String batchNumber,
        BigDecimal quantity,
        LocalDate expirationDate
    ) {
    }

    public record SupplierPortalAsnSerialView(
        UUID id,
        String manufacturerSerialNumber,
        String batchNumber
    ) {
    }

    public record SupplierPortalAsnLineView(
        UUID id,
        UUID supplierPoCommitmentId,
        BigDecimal quantity,
        BigDecimal packageCount,
        String packageType,
        BigDecimal grossWeight,
        BigDecimal netWeight,
        String batchNumber,
        List<String> serialNumbers,
        LocalDate expirationDate,
        List<SupplierPortalAsnBatchView> batches,
        List<SupplierPortalAsnSerialView> serials
    ) {
    }

    public record SupplierPortalAsnView(
        UUID id,
        String asnNumber,
        String status,
        LocalDate noticeDate,
        LocalDate expectedShipDate,
        LocalDate expectedArrivalDate,
        String transportMode,
        String carrierName,
        String vehicleNumber,
        String containerNumber,
        String trackingNumber,
        BigDecimal totalQuantity,
        List<SupplierPortalAsnLineView> lines
    ) {
    }

    public record SupplierPortalCompletionRequest(
        BigDecimal completedAmount,
        BigDecimal completedQuantity,
        LocalDate completionDate,
        String comment
    ) {
    }

    public record SupplierPortalCompletionAttachmentView(
        UUID attachmentId,
        String fileName,
        String contentType
    ) {
    }

    public record SupplierPortalCompletionView(
        UUID id,
        Long version,
        String status,
        BigDecimal completedQuantity,
        BigDecimal completedAmount,
        LocalDate completionDate,
        String comment,
        LocalDateTime submittedAt,
        String buyerReviewStatus,
        BigDecimal acceptedQuantity,
        BigDecimal acceptedAmount,
        BigDecimal remainingAcceptanceQuantity,
        BigDecimal remainingAcceptanceAmount,
        UUID acceptanceActId,
        String acceptanceActNumber,
        List<SupplierPortalCompletionAttachmentView> attachments
    ) {
    }
}
