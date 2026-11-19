package com.enterprise.mm.payload;

import com.enterprise.mm.enums.QuotationSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class RfqProposalDtos {
    private RfqProposalDtos() {
    }

    public record ProposalLineRequest(
        @NotNull UUID rfqItemId,
        @NotNull BigDecimal offeredQuantity,
        BigDecimal unitPrice,
        Integer deliveryDays,
        BigDecimal taxRate,
        boolean noBid,
        String noBidReason,
        String note
    ) {
    }

    public record PortalProposalRequest(
        @NotBlank String currencyCode,
        String paymentTerms,
        String deliveryTerms,
        LocalDate validUntil,
        Boolean taxIncluded,
        @NotEmpty List<@Valid ProposalLineRequest> items
    ) {
    }

    public record PortalReplacementRequest(
        @NotBlank String supersessionReason,
        @Valid @NotNull PortalProposalRequest proposal
    ) {
    }

    public record PortalDeclineRequest(@NotBlank String reason) {
    }

    public record PortalDeclineResponse(
        UUID rfqId,
        UUID supplierId,
        UUID declineRevisionId,
        LocalDateTime declinedAt,
        String reason
    ) {
    }

    public record ProposalAttachmentView(
        UUID id,
        String fileName,
        String contentType,
        Long sizeBytes,
        String attachmentType
    ) {
    }

    public record ManualProposalRequest(
        @NotNull UUID supplierId,
        @NotNull QuotationSource source,
        @NotNull LocalDateTime receivedAt,
        @NotBlank String currencyCode,
        String paymentTerms,
        String deliveryTerms,
        LocalDate validUntil,
        String sourceExplanation,
        String supersessionReason,
        @NotEmpty List<@Valid ProposalLineRequest> items
    ) {
    }

    public record ProposalLineView(
        UUID id,
        UUID rfqItemId,
        BigDecimal offeredQuantity,
        BigDecimal unitPrice,
        BigDecimal totalAmount,
        Integer deliveryDays,
        BigDecimal taxRate,
        boolean noBid,
        String noBidReason,
        String note
    ) {
    }

    public record ProposalView(
        UUID id,
        UUID threadId,
        UUID rfqId,
        UUID supplierId,
        UUID publishedVersionId,
        int revisionNumber,
        String status,
        QuotationSource source,
        LocalDateTime receivedAt,
        LocalDateTime submittedAt,
        String recordedBy,
        String currencyCode,
        BigDecimal totalAmount,
        String paymentTerms,
        String deliveryTerms,
        LocalDate validUntil,
        Boolean taxIncluded,
        String description,
        boolean currentRevision,
        UUID supersedesQuotationId,
        String supersessionReason,
        UUID originalAttachmentId,
        List<ProposalLineView> items
    ) {
    }

    public record ImportedLine(
        int rowNumber,
        UUID rfqItemId,
        BigDecimal unitPrice,
        BigDecimal offeredQuantity,
        Integer deliveryDays,
        BigDecimal taxRate,
        boolean noBid,
        String noBidReason,
        String note
    ) {
    }

    public record ImportIssue(
        String code,
        Integer rowNumber,
        String field,
        String message,
        boolean blocking
    ) {
    }

    public record RfqQuotationImportPreview(
        UUID rfqId,
        UUID publishedVersionId,
        List<ImportedLine> lines,
        List<ImportIssue> issues,
        boolean valid
    ) {
    }
}
