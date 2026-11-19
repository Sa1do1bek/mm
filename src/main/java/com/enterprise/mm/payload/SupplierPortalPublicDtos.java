package com.enterprise.mm.payload;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.enterprise.mm.enums.QuotationSource;

public final class SupplierPortalPublicDtos {
    private SupplierPortalPublicDtos() {
    }

    public record SupplierPortalLoginRequest(
        @Email @NotBlank String loginEmail,
        @NotBlank String inviteToken
    ) {
    }

    public record SupplierPortalLoginResponse(
        String token,
        LocalDateTime expiresAt,
        UUID portalAccountId,
        UUID supplierId,
        String supplierName,
        String status
    ) {
    }

    public record SupplierPortalMe(
        UUID portalAccountId,
        UUID supplierId,
        String supplierName,
        String legalName,
        String contactPerson,
        String loginEmail,
        String portalRole,
        String phone,
        String status,
        LocalDateTime termsAcceptedAt,
        LocalDateTime lastLoginAt,
        LocalDateTime lastSubmissionAt
    ) {
    }

    public record SupplierPortalTeamMember(
        UUID portalAccountId,
        String contactPerson,
        String loginEmail,
        String portalRole,
        String phone,
        String status,
        LocalDateTime invitedAt,
        LocalDateTime activatedAt,
        LocalDateTime lastLoginAt,
        LocalDateTime termsAcceptedAt
    ) {
    }

    public record SupplierPortalTeamInviteRequest(
        @Email @NotBlank String loginEmail,
        @NotBlank String contactPerson,
        String phone,
        String portalRole
    ) {
    }

    public record SupplierPortalRfqSummary(
        UUID id,
        String code,
        String name,
        String status,
        LocalDate dueDate,
        String tenderProcedureType,
        String selectionRule,
        Integer minQuotationsRequired,
        boolean alreadyQuoted,
        String quotationStatus
    ) {
    }

    public record SupplierPortalRfqItem(
        UUID id,
        UUID materialId,
        String materialCode,
        String materialName,
        String unit,
        BigDecimal quantity
    ) {
    }

    public record SupplierPortalBuyer(
        String companyName,
        String address,
        String contactName,
        String email,
        String phone
    ) {
    }

    public record SupplierPortalRfqPosition(
        UUID rfqItemId,
        String materialCode,
        String materialName,
        String specification,
        String unit,
        BigDecimal quantity,
        LocalDate requiredDate
    ) {
    }

    public record SupplierPortalDocument(
        UUID attachmentId,
        String documentType,
        String fileName,
        String contentType
    ) {
    }

    public record SupplierPortalProposalRevision(
        UUID quotationId,
        int revisionNumber,
        String status,
        QuotationSource source,
        LocalDateTime submittedAt,
        String currencyCode,
        BigDecimal totalAmount,
        boolean currentRevision,
        String supersessionReason
    ) {
    }

    public record SupplierPortalRfqDetail(
        UUID rfqId,
        UUID publishedVersionId,
        Integer versionNumber,
        String code,
        String title,
        String description,
        String language,
        String status,
        LocalDate dueDate,
        String tenderProcedureType,
        String selectionRule,
        String responseInstructions,
        SupplierPortalBuyer buyer,
        List<SupplierPortalRfqPosition> positions,
        List<SupplierPortalDocument> documents,
        String responseState,
        SupplierPortalProposalRevision currentProposal,
        List<SupplierPortalProposalRevision> proposalRevisions
    ) {
    }

    public record SupplierPortalQuotationItemRequest(
        @NotNull UUID rfqItemId,
        UUID materialId,
        @NotNull BigDecimal quantity,
        @NotNull BigDecimal unitPrice,
        Integer deliveryDays
    ) {
    }

    public record SupplierPortalQuotationRequest(
        String comment,
        String currencyCode,
        @NotEmpty List<SupplierPortalQuotationItemRequest> items
    ) {
    }

    public record SupplierPortalQuotationResponse(
        UUID quotationId,
        String code,
        String status,
        BigDecimal totalAmount,
        String currencyCode,
        LocalDateTime submittedAt,
        int itemCount
    ) {
    }

    public record SupplierPortalSubmissionRequest(
        @NotBlank String submissionType,
        String referenceEntityType,
        UUID referenceId,
        String payloadJson,
        UUID attachmentId
    ) {
    }
}
