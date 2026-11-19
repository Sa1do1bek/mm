package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DemandPlanWorkspaceDtos {
    private DemandPlanWorkspaceDtos() {
    }

    public record DemandPlanWorkspaceRow(
        UUID id,
        String code,
        String name,
        String requestType,
        String status,
        UUID departmentId,
        String departmentName,
        Integer planYear,
        String periodType,
        Integer quarter,
        LocalDate periodStart,
        LocalDate periodEnd,
        UUID planningCycleId,
        String planningCycleName,
        String ownerUsername,
        String ownerFullName,
        String currencyCode,
        BigDecimal plannedAmount,
        BigDecimal approvedAmount,
        String currentProcessStage,
        String processState,
        String departmentSupplyStatus,
        int itemCount,
        int orderedItemCount,
        int partiallySuppliedItemCount,
        int fullySuppliedItemCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        DemandPlanAction nextAction
    ) {
    }

    public record DemandPlanWorkspaceDetail(
        DemandPlanHeader header,
        SupplySummary supplySummary,
        List<ProcessStage> stages,
        List<ItemCoverage> items,
        ApprovalTimeline approval,
        List<StatusTimelineItem> statusHistory,
        List<RelatedDocumentRef> relatedDocuments,
        AvailableActions availableActions,
        LocalDateTime generatedAt,
        List<SourceWarning> sourceWarnings
    ) {
    }

    public record DemandPlanWorkspaceSummary(
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
        BigDecimal reservedQuantity,
        BigDecimal orderedQuantity,
        BigDecimal receivedOrAcceptedQuantity,
        BigDecimal departmentIssuedQuantity,
        BigDecimal plannedAmount,
        long planCount,
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
        BigDecimal reservedQuantity,
        BigDecimal orderedQuantity,
        BigDecimal receivedOrAcceptedQuantity,
        BigDecimal departmentIssuedQuantity,
        BigDecimal plannedAmount,
        long planCount) {
            this(itemType, materialId, materialName, serviceId, serviceName, unit, requestedQuantity, approvedQuantity, reservedQuantity, orderedQuantity, receivedOrAcceptedQuantity, departmentIssuedQuantity, plannedAmount, planCount, null);
        }
        public ItemSummary withUnitShortName(String value) {
            return new ItemSummary(itemType, materialId, materialName, serviceId, serviceName, unit, requestedQuantity, approvedQuantity, reservedQuantity, orderedQuantity, receivedOrAcceptedQuantity, departmentIssuedQuantity, plannedAmount, planCount, value);
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

    public record DemandPlanHeader(
        UUID id,
        String code,
        String name,
        String requestType,
        String status,
        UUID departmentId,
        String departmentName,
        Integer planYear,
        String periodType,
        Integer quarter,
        LocalDate periodStart,
        LocalDate periodEnd,
        UUID planningCycleId,
        String planningCycleName,
        String ownerUsername,
        String ownerFullName,
        String currencyCode,
        BigDecimal plannedAmount,
        BigDecimal approvedAmount,
        LocalDateTime createdAt,
        Long version
    ) {
    }

    public record SupplySummary(
        String status,
        int itemCount,
        int orderedItemCount,
        int partiallySuppliedItemCount,
        int fullySuppliedItemCount,
        LocalDateTime evidenceGeneratedAt,
        List<String> supplierNames,
        List<String> internalActors
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

    public record ItemCoverage(
        UUID demandPlanItemId,
        String code,
        String name,
        UUID materialId,
        String materialName,
        String unit,
        BigDecimal requestedQuantity,
        BigDecimal approvedQuantity,
        BigDecimal reservedQuantity,
        BigDecimal orderedQuantity,
        BigDecimal receivedOrAcceptedQuantity,
        BigDecimal departmentIssuedQuantity,
        String coverageStatus,
        UUID purchaseRequestItemId,
        LocalDate requiredDate,
        LocalDate needByDate,
        String priority,
        List<String> supplierNames,
        List<RelatedDocumentRef> relatedDocuments,
        BigDecimal stockFulfillmentQuantity,
        BigDecimal remainingStockFulfillmentQuantity,
        BigDecimal approvedNeedQuantity,
        BigDecimal availableToRequestQuantity,
        String unitShortName
    ) {
        public ItemCoverage(UUID demandPlanItemId,
        String code,
        String name,
        UUID materialId,
        String materialName,
        String unit,
        BigDecimal requestedQuantity,
        BigDecimal approvedQuantity,
        BigDecimal reservedQuantity,
        BigDecimal orderedQuantity,
        BigDecimal receivedOrAcceptedQuantity,
        BigDecimal departmentIssuedQuantity,
        String coverageStatus,
        UUID purchaseRequestItemId,
        LocalDate requiredDate,
        LocalDate needByDate,
        String priority,
        List<String> supplierNames,
        List<RelatedDocumentRef> relatedDocuments,
        BigDecimal stockFulfillmentQuantity,
        BigDecimal remainingStockFulfillmentQuantity,
        BigDecimal approvedNeedQuantity,
        BigDecimal availableToRequestQuantity) {
            this(demandPlanItemId, code, name, materialId, materialName, unit, requestedQuantity, approvedQuantity, reservedQuantity, orderedQuantity, receivedOrAcceptedQuantity, departmentIssuedQuantity, coverageStatus, purchaseRequestItemId, requiredDate, needByDate, priority, supplierNames, relatedDocuments, stockFulfillmentQuantity, remainingStockFulfillmentQuantity, approvedNeedQuantity, availableToRequestQuantity, null);
        }
        public ItemCoverage withUnitShortName(String value) {
            return new ItemCoverage(demandPlanItemId, code, name, materialId, materialName, unit, requestedQuantity, approvedQuantity, reservedQuantity, orderedQuantity, receivedOrAcceptedQuantity, departmentIssuedQuantity, coverageStatus, purchaseRequestItemId, requiredDate, needByDate, priority, supplierNames, relatedDocuments, stockFulfillmentQuantity, remainingStockFulfillmentQuantity, approvedNeedQuantity, availableToRequestQuantity, value);
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
        DemandPlanAction primary,
        List<DemandPlanAction> secondary,
        List<DemandPlanAction> overflow
    ) {
    }

    public record DemandPlanAction(
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
