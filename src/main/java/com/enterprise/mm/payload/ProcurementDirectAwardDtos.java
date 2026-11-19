package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public final class ProcurementDirectAwardDtos {
    private ProcurementDirectAwardDtos() {
    }

    public record DirectAwardRequest(
        @NotNull UUID supplierId,
        BigDecimal awardAmount,
        String currencyCode,
        String justification,
        String awardBasisType,
        String basisCode,
        String awardBasisExplanation,
        String awardBasis,
        String evidenceReference,
        BigDecimal marketPriceAmount,
        String marketPriceEvidenceReference,
        String negotiationSummary,
        String conflictOfInterestDeclaration,
        Boolean conflictOfInterestConfirmed
    ) {
        public DirectAwardRequest(
            UUID supplierId,
            BigDecimal awardAmount,
            String currencyCode,
            String justification,
            String awardBasis
        ) {
            this(supplierId, awardAmount, currencyCode, justification, null, null, null, awardBasis,
                null, null, null, null, null, null);
        }

        public DirectAwardRequest(
            UUID supplierId,
            BigDecimal awardAmount,
            String currencyCode,
            String justification,
            String awardBasisType,
            String basisCode,
            String awardBasisExplanation,
            String awardBasis
        ) {
            this(supplierId, awardAmount, currencyCode, justification, awardBasisType, basisCode,
                awardBasisExplanation, awardBasis, null, null, null, null, null, null);
        }
    }

    public record DirectAwardPurchaseOrderRequest(
        LocalDate orderDate,
        LocalDate expectedDeliveryDate,
        UUID contractId
    ) {
    }

    public record DirectAwardResponse(
        UUID id,
        UUID procurementProcedureId,
        UUID methodDecisionId,
        UUID purchaseRequestId,
        UUID supplierId,
        BigDecimal awardAmount,
        String currencyCode,
        String justification,
        String awardBasisType,
        String basisCode,
        String awardBasisExplanation,
        String awardBasis,
        String method,
        String evidenceReference,
        BigDecimal marketPriceAmount,
        String marketPriceEvidenceReference,
        String negotiationSummary,
        String conflictOfInterestDeclaration,
        boolean conflictOfInterestConfirmed,
        String status,
        UUID purchaseOrderId,
        LocalDateTime approvedAt,
        String approvedBy,
        boolean purchaseOrderReady,
        String purchaseOrderBlockedReason
    ) {
    }
}
