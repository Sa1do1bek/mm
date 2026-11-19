package com.enterprise.mm.procurement.command.payload;

import com.enterprise.mm.procurement.command.enums.ProcurementBlockerSeverity;
import com.enterprise.mm.procurement.command.enums.ProcurementCommandStage;
import com.enterprise.mm.procurement.command.enums.ProcurementAllocationMode;
import com.enterprise.mm.payload.SupplierEligibilityDtos.MaterialGroupEligibility;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ProcurementCommandDtos {

    private ProcurementCommandDtos() {
    }

    public record CommandBlocker(
        String code,
        ProcurementBlockerSeverity severity,
        String messageKey,
        Map<String, Object> parameters,
        String documentType,
        UUID documentId,
        UUID lineId,
        String ownerRole,
        UUID ownerUserId,
        String resolutionActionKey
    ) {
    }

    public record CommandAction(
        String key,
        boolean enabled,
        String disabledReasonCode,
        Map<String, Object> disabledReasonParameters,
        boolean commentRequired,
        String invocationKind,
        String documentType,
        UUID documentId
    ) {
    }

    public record AvailableActions(CommandAction primary, List<CommandAction> secondary) {
    }

    public record AllocationBranchRef(
        String documentType,
        UUID documentId,
        UUID documentLineId,
        BigDecimal allocatedQuantity,
        String status
    ) {
    }

    public record LineCoverage(
        UUID purchaseRequestItemId,
        UUID materialId,
        String name,
        String unit,
        BigDecimal requestedQuantity,
        BigDecimal allocatedQuantity,
        BigDecimal orderedQuantity,
        BigDecimal cancelledQuantity,
        BigDecimal remainingQuantity,
        BigDecimal coveragePercent,
        List<AllocationBranchRef> branches
    ) {
    }

    public record SourcingCoverageSnapshot(
        ProcurementAllocationMode allocationMode,
        List<LineCoverage> lines,
        LocalDateTime generatedAt
    ) {
    }

    public record QueueDefinition(
        com.enterprise.mm.procurement.command.enums.ProcurementQueueKey key,
        String titleKey,
        long count,
        boolean available,
        String unavailableReasonCode,
        ProcurementCommandSearchRequest appliedFilters
    ) {
        public QueueDefinition(
            com.enterprise.mm.procurement.command.enums.ProcurementQueueKey key,
            String titleKey,
            long count,
            boolean available,
            String unavailableReasonCode
        ) {
            this(key, titleKey, count, available, unavailableReasonCode, null);
        }
    }

    public record ProcurementCommandCaseRow(
        UUID purchaseRequestId,
        String purchaseRequestCode,
        String purchaseRequestName,
        ProcurementCommandStage stage,
        String requester,
        UUID departmentId,
        LocalDate requiredDate,
        BigDecimal totalAmount,
        String currencyCode,
        ProcurementAllocationMode allocationMode,
        ProcurementBlockerSeverity severity,
        String blockerCode,
        String nextActionKey,
        UUID ownerUserId,
        String ownerUsername,
        LocalDate dueDate,
        boolean overdue,
        long rfqCount,
        long purchaseOrderCount,
        boolean integrationImpact
    ) {
    }

    public record ReadinessCheck(
        String code,
        boolean passed,
        boolean blocking,
        Map<String, Object> parameters
    ) {
    }

    public record DispatchReadiness(
        boolean ready,
        List<ReadinessCheck> checks,
        List<MaterialGroupEligibility> materialGroupEligibility
    ) {
        public DispatchReadiness(boolean ready, List<ReadinessCheck> checks) {
            this(ready, checks, List.of());
        }
    }

    public record CommandSummary(
        long openCases,
        long overdueCases,
        long blockedCases,
        long readyToDispatch,
        LocalDateTime generatedAt
    ) {
    }

    public record CaseHeader(
        UUID purchaseRequestId,
        String code,
        String name,
        String status,
        String requester,
        UUID departmentId,
        LocalDate requiredDate,
        BigDecimal totalAmount,
        String currencyCode
    ) {
    }

    public record RelatedDocument(
        String documentType,
        UUID documentId,
        String code,
        String status
    ) {
    }

    public record EntityRef(UUID id, String code, String displayName, String status) {
    }

    public record PurchaseOrderReadiness(
        UUID purchaseOrderId,
        String code,
        String status,
        EntityRef supplier,
        DispatchReadiness readiness
    ) {
        public PurchaseOrderReadiness(UUID purchaseOrderId, String code, String status, DispatchReadiness readiness) {
            this(purchaseOrderId, code, status, null, readiness);
        }
    }

    public record LifecycleItem(
        String stage,
        String documentType,
        UUID documentId,
        String code,
        String status,
        String displayName,
        String route
    ) {
    }

    public record AssignmentContext(
        UUID id,
        UUID ownerUserId,
        String ownerUsername,
        String ownerRole,
        LocalDate dueDate,
        String severity,
        boolean escalated,
        int escalationLevel,
        String reason,
        Long version
    ) {
    }

    public record ApprovalTaskContext(
        UUID id,
        Integer stepOrder,
        String status,
        String assigneeRole,
        UUID assigneeUserId,
        String assigneeUsername,
        String actedBy,
        LocalDateTime actedAt,
        String comment
    ) {
    }

    public record ApprovalContext(
        UUID instanceId,
        String moduleKey,
        UUID documentId,
        String documentCode,
        String status,
        Integer currentStepOrder,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        List<ApprovalTaskContext> tasks
    ) {
    }

    public record EvidenceContext(
        UUID id,
        String entityType,
        UUID entityId,
        String businessDocumentCode,
        String displayName,
        String attachmentType,
        String contentType,
        Long sizeBytes,
        LocalDateTime uploadedAt,
        boolean previewable,
        boolean downloadable
    ) {
    }

    public record IntegrationImpactContext(
        UUID id,
        UUID aggregateId,
        String businessDocumentCode,
        String eventType,
        String status,
        Integer retryCount,
        String lastError,
        LocalDateTime createdAt
    ) {
    }

    public record ProcurementCommandCaseDetail(
        CaseHeader header,
        SourcingCoverageSnapshot coverage,
        List<CommandBlocker> blockers,
        AvailableActions availableActions,
        List<PurchaseOrderReadiness> purchaseOrders,
        List<RelatedDocument> relatedDocuments,
        List<String> sourceWarnings,
        List<LifecycleItem> lifecycle,
        AssignmentContext assignment,
        List<ApprovalContext> approvals,
        List<EvidenceContext> evidence,
        List<IntegrationImpactContext> integrationImpact,
        LocalDateTime generatedAt
    ) {
        public ProcurementCommandCaseDetail(
            CaseHeader header,
            SourcingCoverageSnapshot coverage,
            List<CommandBlocker> blockers,
            AvailableActions availableActions,
            List<PurchaseOrderReadiness> purchaseOrders,
            List<RelatedDocument> relatedDocuments,
            List<String> sourceWarnings,
            LocalDateTime generatedAt
        ) {
            this(
                header, coverage, blockers, availableActions, purchaseOrders, relatedDocuments, sourceWarnings,
                List.of(), null, List.of(), List.of(), List.of(), generatedAt);
        }
    }

    public record QuotationComparisonRow(
        UUID quotationId,
        String code,
        UUID supplierId,
        String supplierName,
        BigDecimal totalAmount,
        String currencyCode,
        BigDecimal technicalScore,
        BigDecimal deliveryScore,
        BigDecimal priceScore,
        BigDecimal totalScore,
        boolean selected
    ) {
    }

    public record CommandTimelineEntry(
        LocalDateTime eventAt,
        String eventType,
        String actor,
        String documentType,
        UUID documentId,
        String summaryKey,
        Map<String, Object> parameters
    ) {
    }

    public record AssignmentRequest(
        UUID ownerUserId,
        String ownerUsername,
        String ownerRole,
        LocalDate dueDate,
        String severity,
        boolean escalated,
        String reason,
        Long version
    ) {
    }

    public record ClaimRequest(LocalDate dueDate, String reason, Long version) {
    }

    public record EscalationRequest(String severity, String reason, Long version) {
    }

    public record AssignmentResponse(
        UUID id,
        UUID purchaseRequestId,
        UUID ownerUserId,
        String ownerUsername,
        String ownerRole,
        LocalDate dueDate,
        String severity,
        boolean escalated,
        int escalationLevel,
        String reason,
        Long version
    ) {
    }

    @Builder
    public record CaseFacts(
        String purchaseRequestStatus,
        List<ProcurementCommandStage> branchStages,
        List<String> rfqStatuses,
        boolean approvalPending,
        boolean approvalRejected,
        boolean quotationReady,
        boolean supplierBlocked,
        boolean accreditationFailed,
        boolean tenderBlocked,
        boolean poBlocked,
        boolean poDraft,
        boolean poReady,
        boolean contractInvalid,
        boolean integrationFailed,
        boolean overdue
    ) {
    }
}
