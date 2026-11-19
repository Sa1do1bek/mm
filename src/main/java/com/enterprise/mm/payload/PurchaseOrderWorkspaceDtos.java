package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PurchaseOrderWorkspaceDtos {
    private PurchaseOrderWorkspaceDtos() {
    }

    public record PurchaseOrderWorkspaceRow(
        UUID id,
        String code,
        String name,
        String requestType,
        String status,
        UUID supplierId,
        String supplierCode,
        String supplierName,
        UUID purchaseRequestId,
        String purchaseRequestCode,
        String purchaseRequestName,
        UUID demandPlanId,
        String demandPlanCode,
        String demandPlanName,
        LocalDate orderDate,
        LocalDate expectedDeliveryDate,
        String currencyCode,
        BigDecimal totalAmount,
        String currentProcessStage,
        String processState,
        int itemCount,
        int lineageAvailableItemCount,
        int partiallyFulfilledItemCount,
        int fullyFulfilledItemCount,
        LocalDateTime updatedAt,
        PurchaseOrderAction nextAction
    ) {
    }

    public record PurchaseOrderWorkspaceDetail(
        PurchaseOrderHeader header,
        FulfillmentSummary fulfillmentSummary,
        PayablesSummary payablesSummary,
        List<ProcessStage> stages,
        List<LineCoverage> items,
        ApprovalTimeline approval,
        List<StatusTimelineItem> statusHistory,
        List<RelatedDocumentRef> relatedDocuments,
        AvailableActions availableActions,
        LocalDateTime generatedAt,
        List<SourceWarning> sourceWarnings
    ) {
    }

    public record PurchaseOrderWorkspaceSummary(
        List<StatusSummaryRow> rows,
        SummaryTotal total
    ) {
    }

    public record StatusSummaryRow(
        String status,
        long count,
        BigDecimal plannedAmount,
        BigDecimal approvedAmount,
        String currencyCode,
        List<ItemSummary> items
    ) {
    }

    public record ItemSummary(
        String itemType,
        UUID materialId,
        String materialName,
        UUID serviceId,
        String serviceName,
        String unit,
        BigDecimal requestedQuantity,
        BigDecimal approvedQuantity,
        BigDecimal orderedQuantity,
        BigDecimal receivedOrAcceptedQuantity,
        BigDecimal plannedAmount,
        long orderCount,
        String unitShortName
    ) {
        public ItemSummary(String itemType,
        UUID materialId,
        String materialName,
        UUID serviceId,
        String serviceName,
        String unit,
        BigDecimal requestedQuantity,
        BigDecimal approvedQuantity,
        BigDecimal orderedQuantity,
        BigDecimal receivedOrAcceptedQuantity,
        BigDecimal plannedAmount,
        long orderCount) {
            this(itemType, materialId, materialName, serviceId, serviceName, unit, requestedQuantity, approvedQuantity, orderedQuantity, receivedOrAcceptedQuantity, plannedAmount, orderCount, null);
        }
        public ItemSummary withUnitShortName(String value) {
            return new ItemSummary(itemType, materialId, materialName, serviceId, serviceName, unit, requestedQuantity, approvedQuantity, orderedQuantity, receivedOrAcceptedQuantity, plannedAmount, orderCount, value);
        }

    }

    public record SummaryTotal(
        long count,
        BigDecimal plannedAmount,
        BigDecimal approvedAmount,
        String currencyCode,
        long withApprovalFlowCount
    ) {
    }

    public record PurchaseOrderHeader(
        UUID id,
        String code,
        String name,
        String requestType,
        String status,
        UUID supplierId,
        String supplierCode,
        String supplierName,
        UUID purchaseRequestId,
        String purchaseRequestCode,
        String purchaseRequestName,
        UUID demandPlanId,
        String demandPlanCode,
        String demandPlanName,
        UUID contractId,
        String contractCode,
        String contractName,
        LocalDate orderDate,
        LocalDate expectedDeliveryDate,
        String currencyCode,
        BigDecimal totalAmount,
        Long version
    ) {
    }

    public record FulfillmentSummary(
        int itemCount,
        int lineageAvailableItemCount,
        int partiallyFulfilledItemCount,
        int fullyFulfilledItemCount,
        LocalDateTime evidenceGeneratedAt
    ) {
    }

    public record PayablesSummary(
        boolean evidenceAvailable,
        BigDecimal evidenceAmount,
        BigDecimal invoicedAmount,
        BigDecimal remainingInvoiceableAmount,
        BigDecimal paidAmount,
        BigDecimal outstandingPayableAmount,
        int invoiceCount,
        int paymentCount,
        String primarySourceDocumentType,
        UUID primarySourceDocumentId
    ) {
    }

    public record ProcessStage(
        String key,
        String state,
        boolean current,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        String responsible,
        String actor,
        String summary,
        List<RelatedDocumentRef> relatedDocuments
    ) {
    }

    public record SourceAllocationLineage(
        UUID allocationId,
        UUID purchaseRequestItemId,
        String purchaseRequestItemCode,
        UUID demandPlanItemId,
        String demandPlanItemCode,
        UUID demandPlanId,
        String demandPlanCode,
        BigDecimal orderedQuantity
    ) {
    }

    public record LineCoverage(
        UUID purchaseOrderItemId,
        String code,
        String name,
        String lineType,
        UUID materialId,
        String materialName,
        String unit,
        BigDecimal orderedQuantity,
        BigDecimal receivedOrAcceptedQuantity,
        String coverageStatus,
        boolean sourceAllocationAvailable,
        List<SourceAllocationLineage> sourceAllocations,
        List<RelatedDocumentRef> relatedDocuments,
        String unitShortName
    ) {
        public LineCoverage(UUID purchaseOrderItemId,
        String code,
        String name,
        String lineType,
        UUID materialId,
        String materialName,
        String unit,
        BigDecimal orderedQuantity,
        BigDecimal receivedOrAcceptedQuantity,
        String coverageStatus,
        boolean sourceAllocationAvailable,
        List<SourceAllocationLineage> sourceAllocations,
        List<RelatedDocumentRef> relatedDocuments) {
            this(purchaseOrderItemId, code, name, lineType, materialId, materialName, unit, orderedQuantity, receivedOrAcceptedQuantity, coverageStatus, sourceAllocationAvailable, sourceAllocations, relatedDocuments, null);
        }
        public LineCoverage withUnitShortName(String value) {
            return new LineCoverage(purchaseOrderItemId, code, name, lineType, materialId, materialName, unit, orderedQuantity, receivedOrAcceptedQuantity, coverageStatus, sourceAllocationAvailable, sourceAllocations, relatedDocuments, value);
        }

    }

    public record ApprovalTimeline(
        String moduleKey,
        UUID instanceId,
        String status,
        List<ApprovalStep> steps
    ) {
    }

    public record ApprovalStep(
        Integer stepOrder,
        String stepName,
        String assignee,
        String state,
        String actingUser,
        LocalDateTime actionTime,
        String comment
    ) {
    }

    public record StatusTimelineItem(
        UUID id,
        String action,
        String actor,
        String details,
        LocalDateTime occurredAt
    ) {
    }

    public record RelatedDocumentRef(
        String documentType,
        UUID documentId,
        String moduleKey,
        String code,
        String displayName,
        String status
    ) {
    }

    public record AvailableActions(
        PurchaseOrderAction primary,
        List<PurchaseOrderAction> secondary,
        List<PurchaseOrderAction> overflow
    ) {
    }

    public record PurchaseOrderAction(
        String key,
        boolean enabled,
        String disabledReasonCode,
        Map<String, String> disabledReasonParams,
        boolean confirmationRequired,
        String invocationKind,
        RelatedDocumentRef targetDocument
    ) {
    }

    public record SourceWarning(
        String code,
        String message,
        UUID sourceId
    ) {
    }
}
