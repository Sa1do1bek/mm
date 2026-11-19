package com.enterprise.mm.supplier.po.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Input commands for {@link SupplierPoResponseService}. Phase B's public supplier-portal
 * controller maps its request DTOs onto these before calling the service. */
public final class SupplierPoResponseCommands {
    private SupplierPoResponseCommands() {
    }

    public record CommitmentCommand(BigDecimal proposedQuantity, LocalDate proposedDate) {
    }

    /** decision is one of ACCEPTED, PARTIALLY_ACCEPTED, CHANGE_REQUESTED, REJECTED. */
    public record LineDecisionCommand(
        UUID purchaseOrderItemId,
        String decision,
        BigDecimal acceptedQuantity,
        String reasonCode,
        String supplierComment,
        List<CommitmentCommand> commitments
    ) {
        public LineDecisionCommand(
            UUID purchaseOrderItemId,
            String decision,
            String reasonCode,
            String supplierComment,
            List<CommitmentCommand> commitments
        ) {
            this(purchaseOrderItemId, decision, null, reasonCode, supplierComment, commitments);
        }
    }
}
