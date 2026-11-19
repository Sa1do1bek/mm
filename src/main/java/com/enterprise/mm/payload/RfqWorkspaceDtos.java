package com.enterprise.mm.payload;

import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.QuotationSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class RfqWorkspaceDtos {
    private RfqWorkspaceDtos() {
    }

    public record RfqWorkspaceFilter(
        List<String> statuses,
        LocalDate dueDateFrom,
        LocalDate dueDateTo,
        Boolean overdue,
        String responsibleBuyer,
        UUID purchaseRequestId,
        UUID materialId,
        UUID materialGroupId,
        UUID supplierId,
        String responseProgress,
        List<String> deliveryStatuses,
        LocalDateTime createdAtFrom,
        LocalDateTime createdAtTo,
        LocalDateTime publishedAtFrom,
        LocalDateTime publishedAtTo,
        String search
    ) {
        public RfqWorkspaceFilter {
            statuses = statuses == null ? List.of() : List.copyOf(statuses);
            deliveryStatuses = deliveryStatuses == null ? List.of() : List.copyOf(deliveryStatuses);
        }
    }

    public record RfqWorkspaceRow(
        UUID rfqId,
        String code,
        String name,
        String title,
        String status,
        String responsibleBuyer,
        int sourcePurchaseRequestCount,
        int positionCount,
        int invitedSupplierCount,
        int proposalCount,
        int failedDeliveryCount,
        LocalDate dueDate,
        boolean overdue,
        LocalDateTime createdAt,
        LocalDateTime publishedAt
    ) {
    }

    public record RfqOverview(
        UUID rfqId,
        String code,
        String name,
        String title,
        String description,
        String status,
        LocalDate dueDate,
        String responsibleBuyer,
        String tenderProcedureType,
        String selectionRule,
        Integer minQuotationsRequired,
        String currencyCode,
        String packageLanguage,
        UUID activePublishedVersionId,
        Integer activeVersionNumber,
        String responseProgress,
        int invitedSupplierCount,
        int proposalCount,
        int failedDeliveryCount,
        LocalDateTime createdAt,
        LocalDateTime publishedAt,
        LocalDateTime closedAt
    ) {
    }

    public record PurchaseRequestSource(
        UUID purchaseRequestId,
        String code,
        String title,
        String status,
        String requestedBy,
        UUID departmentId,
        LocalDate requiredDate
    ) {
    }

    public record RfqPosition(
        UUID rfqItemId,
        ProcurementLineType lineType,
        UUID materialId,
        String materialCode,
        String materialName,
        UUID materialGroupId,
        String materialGroupName,
        String specification,
        String unit,
        BigDecimal quantity,
        String serviceDescription,
        String serviceCategory,
        String period,
        String stage,
        LocalDate requiredDate,
        String unitShortName
    ) {
        public RfqPosition(UUID rfqItemId,
        ProcurementLineType lineType,
        UUID materialId,
        String materialCode,
        String materialName,
        UUID materialGroupId,
        String materialGroupName,
        String specification,
        String unit,
        BigDecimal quantity,
        String serviceDescription,
        String serviceCategory,
        String period,
        String stage,
        LocalDate requiredDate) {
            this(rfqItemId, lineType, materialId, materialCode, materialName, materialGroupId, materialGroupName, specification, unit, quantity, serviceDescription, serviceCategory, period, stage, requiredDate, null);
        }
        public RfqPosition withUnitShortName(String value) {
            return new RfqPosition(rfqItemId, lineType, materialId, materialCode, materialName, materialGroupId, materialGroupName, specification, unit, quantity, serviceDescription, serviceCategory, period, stage, requiredDate, value);
        }

    }

    public record DeliveryAttempt(
        UUID deliveryId,
        long version,
        UUID publishedVersionId,
        String status,
        String recipientEmail,
        String recipientName,
        Integer retryCount,
        LocalDateTime firstSentAt,
        LocalDateTime lastSentAt,
        LocalDateTime deliveredAt,
        LocalDateTime portalOpenedAt,
        LocalDateTime declinedAt,
        LocalDateTime quotationReceivedAt,
        String lastErrorCategory
    ) {
    }

    public record InvitedSupplierProgress(
        UUID invitationId,
        UUID supplierId,
        String supplierCode,
        String supplierName,
        String invitationStatus,
        String recipientName,
        String recipientEmail,
        LocalDateTime portalOpenedAt,
        LocalDateTime declinedAt,
        LocalDateTime quotationReceivedAt,
        String currentProposalStatus,
        QuotationSource currentProposalSource,
        UUID currentProposalId,
        List<DeliveryAttempt> deliveries
    ) {
    }

    public record ProposalLineSummary(
        UUID quotationItemId,
        String name,
        UUID rfqItemId,
        UUID materialId,
        String materialCode,
        String materialName,
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

    public record ProposalRevisionSummary(
        UUID quotationId,
        UUID threadId,
        UUID supplierId,
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
        boolean currentRevision,
        UUID supersedesQuotationId,
        String supersessionReason,
        UUID originalAttachmentId,
        List<ProposalLineSummary> lines
    ) {
    }

    public record ProposalSummary(
        UUID supplierId,
        String supplierCode,
        String supplierName,
        ProposalRevisionSummary current,
        List<ProposalRevisionSummary> revisions
    ) {
    }

    public record DocumentSummary(
        UUID attachmentId,
        UUID publishedVersionId,
        Integer versionNumber,
        String documentType,
        String fileName,
        String contentType,
        Long sizeBytes,
        LocalDateTime createdAt
    ) {
    }

    public record AuditSummary(
        UUID auditId,
        String name,
        String entityType,
        UUID entityId,
        String action,
        String username,
        String details,
        LocalDateTime occurredAt
    ) {
    }

    public record RfqWorkspaceDetail(
        RfqOverview overview,
        List<PurchaseRequestSource> purchaseRequests,
        List<RfqPosition> positions,
        List<InvitedSupplierProgress> invitedSuppliers,
        List<ProposalSummary> proposals,
        List<DocumentSummary> documents,
        List<AuditSummary> audit
    ) {
    }

    public record RfqQuotationComparison(
        UUID rfqId,
        boolean comparable,
        List<String> currencyCodes,
        List<ProposalSummary> proposals
    ) {
    }
}
