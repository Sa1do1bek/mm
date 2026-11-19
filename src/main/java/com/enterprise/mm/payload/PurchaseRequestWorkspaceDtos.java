package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PurchaseRequestWorkspaceDtos {
    private PurchaseRequestWorkspaceDtos() {
    }

    public record PurchaseRequestWorkspaceRow(
        UUID id,
        String code,
        String name,
        String requestType,
        String status,
        UUID departmentId,
        String departmentName,
        UUID demandPlanId,
        String demandPlanCode,
        String demandPlanName,
        String requestedBy,
        LocalDate requiredDate,
        String currencyCode,
        BigDecimal totalAmount,
        String currentProcessStage,
        String processState,
        int itemCount,
        int orderedItemCount,
        int partiallyFulfilledItemCount,
        int fullyFulfilledItemCount,
        long rfqCount,
        long purchaseOrderCount,
        LocalDateTime updatedAt,
        PurchaseRequestAction nextAction
    ) {
    }

    public record PurchaseRequestWorkspaceDetail(
        PurchaseRequestHeader header,
        FulfillmentSummary fulfillmentSummary,
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

    public record PurchaseRequestWorkspaceSummary(
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
        long requestCount,
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
        long requestCount) {
            this(itemType, materialId, materialName, serviceId, serviceName, unit, requestedQuantity, approvedQuantity, orderedQuantity, receivedOrAcceptedQuantity, plannedAmount, requestCount, null);
        }
        public ItemSummary withUnitShortName(String value) {
            return new ItemSummary(itemType, materialId, materialName, serviceId, serviceName, unit, requestedQuantity, approvedQuantity, orderedQuantity, receivedOrAcceptedQuantity, plannedAmount, requestCount, value);
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

    public record PurchaseRequestHeader(
        UUID id,
        String code,
        String name,
        String requestType,
        String status,
        UUID departmentId,
        String departmentName,
        UUID demandPlanId,
        String demandPlanCode,
        String demandPlanName,
        String requestedBy,
        LocalDate requiredDate,
        String currencyCode,
        BigDecimal totalAmount,
        Long version
    ) {
    }

    public record FulfillmentSummary(
        int itemCount,
        int orderedItemCount,
        int partiallyFulfilledItemCount,
        int fullyFulfilledItemCount,
        LocalDateTime evidenceGeneratedAt,
        List<String> supplierNames
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

    public record LineCoverage(
        UUID purchaseRequestItemId,
        String code,
        String name,
        String lineType,
        UUID materialId,
        String materialName,
        String unit,
        BigDecimal requestedQuantity,
        BigDecimal orderedQuantity,
        BigDecimal receivedOrAcceptedQuantity,
        String coverageStatus,
        UUID sourceDemandPlanItemId,
        String sourceDemandPlanItemCode,
        UUID sourceDemandPlanId,
        String sourceDemandPlanCode,
        LocalDate requiredDate,
        List<String> supplierNames,
        List<RelatedDocumentRef> relatedDocuments,
        String unitShortName
    ) {
        public LineCoverage(UUID purchaseRequestItemId,
        String code,
        String name,
        String lineType,
        UUID materialId,
        String materialName,
        String unit,
        BigDecimal requestedQuantity,
        BigDecimal orderedQuantity,
        BigDecimal receivedOrAcceptedQuantity,
        String coverageStatus,
        UUID sourceDemandPlanItemId,
        String sourceDemandPlanItemCode,
        UUID sourceDemandPlanId,
        String sourceDemandPlanCode,
        LocalDate requiredDate,
        List<String> supplierNames,
        List<RelatedDocumentRef> relatedDocuments) {
            this(purchaseRequestItemId, code, name, lineType, materialId, materialName, unit, requestedQuantity, orderedQuantity, receivedOrAcceptedQuantity, coverageStatus, sourceDemandPlanItemId, sourceDemandPlanItemCode, sourceDemandPlanId, sourceDemandPlanCode, requiredDate, supplierNames, relatedDocuments, null);
        }
        public LineCoverage withUnitShortName(String value) {
            return new LineCoverage(purchaseRequestItemId, code, name, lineType, materialId, materialName, unit, requestedQuantity, orderedQuantity, receivedOrAcceptedQuantity, coverageStatus, sourceDemandPlanItemId, sourceDemandPlanItemCode, sourceDemandPlanId, sourceDemandPlanCode, requiredDate, supplierNames, relatedDocuments, value);
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
        String status,
        List<RelatedDocumentField> fields
    ) {
        public RelatedDocumentRef(
            String documentType,
            UUID documentId,
            String moduleKey,
            String code,
            String displayName,
            String status
        ) {
            this(documentType, documentId, moduleKey, code, displayName, status, List.of());
        }
    }

    public record RelatedDocumentField(
        String key,
        String value
    ) {
    }

    public record AvailableActions(
        PurchaseRequestAction primary,
        List<PurchaseRequestAction> secondary,
        List<PurchaseRequestAction> overflow
    ) {
    }

    public record PurchaseRequestAction(
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
