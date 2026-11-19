package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Internal buyer-facing DTOs for the "Supplier collaboration" tab on PO detail.
 * Field names match the frontend contract given in docs/CURRENT_TASK.md exactly --
 * the frontend was built against this shape and cannot see this code.
 */
public final class PurchaseOrderCollaborationDtos {
    private PurchaseOrderCollaborationDtos() {
    }

    public record DispatchView(
        UUID id,
        Long version,
        String dispatchStatus,
        String recipientEmail,
        Long purchaseOrderVersion,
        boolean isCurrent,
        LocalDateTime queuedAt,
        LocalDateTime sentAt,
        LocalDateTime deliveredAt,
        LocalDateTime viewedAt,
        LocalDate responseDueDate,
        String lastDeliveryError,
        LocalDateTime supersededAt,
        LocalDateTime cancelledAt
    ) {
    }

    public record CommitmentView(
        UUID id,
        Long version,
        BigDecimal proposedQuantity,
        LocalDate proposedDate,
        BigDecimal confirmedQuantity,
        LocalDate confirmedDate,
        String status,
        UUID deliveryScheduleId,
        UUID supersededCommitmentId,
        boolean reconfirmationAllowed,
        String reconfirmationBlockedReason,
        UUID activeReconfirmationResponseId
    ) {
    }

    public record ResponseLineView(
        UUID id,
        UUID purchaseOrderItemId,
        String decision,
        BigDecimal orderedQuantity,
        BigDecimal openQuantityAtDispatch,
        BigDecimal acceptedQuantity,
        BigDecimal unconfirmedQuantity,
        String reasonCode,
        String supplierComment,
        List<CommitmentView> commitments
    ) {
    }

    public record ResponseView(
        UUID id,
        Long version,
        int responseRevision,
        String responseType,
        String responseStatus,
        String supplierComment,
        String rejectionReason,
        LocalDateTime submittedAt,
        String buyerDecisionBy,
        LocalDateTime buyerDecisionAt,
        String buyerDecisionComment,
        List<ResponseLineView> lines
    ) {
    }

    public record AsnSummary(
        long submittedCount,
        BigDecimal totalConfirmedQuantity,
        BigDecimal totalShippedQuantity
    ) {
    }

    public record ReceiptSummary(
        BigDecimal receivedQuantity,
        BigDecimal rejectedQuantity,
        BigDecimal damagedQuantity
    ) {
    }

    public record CollaborationStatusResponse(
        String collaborationStatus,
        boolean legacy,
        DispatchView currentDispatch,
        ResponseView currentResponse,
        List<ResponseView> responseHistory,
        AsnSummary asnSummary,
        ReceiptSummary receiptSummary
    ) {
    }

    /** Buyer command payload for accept/reject/request-revision/resend/cancel-commitment,
     * same shape as {@code SupplierAccreditationCommandDtos}'s versioned commands. */
    public record BuyerCollaborationCommand(
        Long expectedVersion,
        String reason,
        String comment
    ) {
    }
}
