package com.enterprise.mm.payload;

import com.enterprise.mm.payload.ApprovalDtos.ApprovalHistoryResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalInstanceResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ServiceAcceptanceWorkspaceDtos {
    private ServiceAcceptanceWorkspaceDtos() {
    }

    public record Summary(
        long activeRequests,
        long activeExecutions,
        long awaitingAcceptance,
        long partialOrRework,
        long paymentReady,
        long overdueExecutions,
        long integrationIssues,
        List<MetricValue> metrics
    ) {
        public Summary(
            long activeRequests,
            long activeExecutions,
            long awaitingAcceptance,
            long partialOrRework,
            long paymentReady,
            long overdueExecutions,
            long integrationIssues
        ) {
            this(activeRequests, activeExecutions, awaitingAcceptance, partialOrRework, paymentReady,
                overdueExecutions, integrationIssues, List.of());
        }
    }

    public record MetricValue(
        String key,
        boolean available,
        BigDecimal value,
        String currency,
        BigDecimal trend,
        String unavailableReason,
        Map<String, String> appliedFilters
    ) {
    }

    public record ManagementSeriesItem(
        String key,
        String label,
        BigDecimal value,
        boolean available,
        String unavailableReason,
        String currency,
        Map<String, String> appliedFilters
    ) {
    }

    public record ServicesManagementDashboard(
        List<MetricValue> metrics,
        List<ManagementSeriesItem> lifecycleWaterfall,
        List<ManagementSeriesItem> backlogAging,
        List<ManagementSeriesItem> exposureBreakdown,
        List<ManagementSeriesItem> paymentForecast,
        List<ManagementSeriesItem> integrationTrend,
        List<ManagementSeriesItem> supplierExposure,
        List<ManagementSeriesItem> categoryExposure,
        List<ManagementSeriesItem> departmentExposure,
        List<ManagementSeriesItem> contractExposure,
        String baseCurrency,
        LocalDateTime fxSnapshotAt
    ) {
    }

    public record IntakeOption(String value, String label, String description) {
    }

    public record ServiceRequestIntakeOptions(
        List<IntakeOption> suppliers,
        List<IntakeOption> departments,
        List<IntakeOption> owners,
        List<IntakeOption> currencies,
        List<IntakeOption> units,
        List<String> approvalPreview
    ) {
        public ServiceRequestIntakeOptions {
            suppliers = suppliers == null ? List.of() : List.copyOf(suppliers);
            departments = departments == null ? List.of() : List.copyOf(departments);
            owners = owners == null ? List.of() : List.copyOf(owners);
            currencies = currencies == null ? List.of() : List.copyOf(currencies);
            units = units == null ? List.of() : List.copyOf(units);
            approvalPreview = approvalPreview == null ? List.of() : List.copyOf(approvalPreview);
        }
    }

    public record QueueDescriptor(
        String key,
        String titleKey,
        long count,
        Map<String, String> appliedFilters
    ) {
    }

    public record CaseListItem(
        UUID caseId,
        String originType,
        UUID originId,
        UUID requestId,
        UUID serviceOrderId,
        UUID serviceActId,
        UUID workCompletionActId,
        String code,
        String title,
        String status,
        String phase,
        String urgency,
        UUID supplierId,
        String supplierName,
        UUID contractId,
        String contractCode,
        UUID responsibleId,
        String responsibleName,
        LocalDate plannedEndDate,
        LocalDate dueDate,
        String slaState,
        boolean overdue,
        BigDecimal progressPercent,
        String acceptanceMode,
        BigDecimal orderedQuantity,
        BigDecimal acceptedQuantity,
        BigDecimal rejectedQuantity,
        BigDecimal remainingQuantity,
        String uomCode,
        BigDecimal orderedAmount,
        BigDecimal acceptedAmount,
        BigDecimal rejectedAmount,
        BigDecimal remainingAmount,
        String currencyCode,
        String latestActStatus,
        long openClaimCount,
        long blockerCount,
        String nextAction,
        Long version,
        String uomShortName
    ) {
        public CaseListItem(UUID caseId,
        String originType,
        UUID originId,
        UUID requestId,
        UUID serviceOrderId,
        UUID serviceActId,
        UUID workCompletionActId,
        String code,
        String title,
        String status,
        String phase,
        String urgency,
        UUID supplierId,
        String supplierName,
        UUID contractId,
        String contractCode,
        UUID responsibleId,
        String responsibleName,
        LocalDate plannedEndDate,
        LocalDate dueDate,
        String slaState,
        boolean overdue,
        BigDecimal progressPercent,
        String acceptanceMode,
        BigDecimal orderedQuantity,
        BigDecimal acceptedQuantity,
        BigDecimal rejectedQuantity,
        BigDecimal remainingQuantity,
        String uomCode,
        BigDecimal orderedAmount,
        BigDecimal acceptedAmount,
        BigDecimal rejectedAmount,
        BigDecimal remainingAmount,
        String currencyCode,
        String latestActStatus,
        long openClaimCount,
        long blockerCount,
        String nextAction,
        Long version) {
            this(caseId, originType, originId, requestId, serviceOrderId, serviceActId, workCompletionActId, code, title, status, phase, urgency, supplierId, supplierName, contractId, contractCode, responsibleId, responsibleName, plannedEndDate, dueDate, slaState, overdue, progressPercent, acceptanceMode, orderedQuantity, acceptedQuantity, rejectedQuantity, remainingQuantity, uomCode, orderedAmount, acceptedAmount, rejectedAmount, remainingAmount, currencyCode, latestActStatus, openClaimCount, blockerCount, nextAction, version, null);
        }
        public CaseListItem withUnitShortName(String value) {
            return new CaseListItem(caseId, originType, originId, requestId, serviceOrderId, serviceActId, workCompletionActId, code, title, status, phase, urgency, supplierId, supplierName, contractId, contractCode, responsibleId, responsibleName, plannedEndDate, dueDate, slaState, overdue, progressPercent, acceptanceMode, orderedQuantity, acceptedQuantity, rejectedQuantity, remainingQuantity, uomCode, orderedAmount, acceptedAmount, rejectedAmount, remainingAmount, currencyCode, latestActStatus, openClaimCount, blockerCount, nextAction, version, value);
        }

        public CaseListItem(
            UUID requestId,
            String code,
            String title,
            String status,
            String urgency,
            String supplierName,
            String responsibleName,
            LocalDate plannedEndDate,
            BigDecimal progressPercent,
            String latestActStatus,
            long openClaimCount,
            long blockerCount,
            String nextAction,
            Long version
        ) {
            this(
                requestId,
                "SERVICE_REQUEST",
                requestId,
                requestId,
                null,
                null,
                null,
                code,
                title,
                status,
                null,
                urgency,
                null,
                supplierName,
                null,
                null,
                null,
                responsibleName,
                plannedEndDate,
                plannedEndDate,
                null,
                false,
                progressPercent,
                "AMOUNT",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                latestActStatus,
                openClaimCount,
                blockerCount,
                nextAction,
                version
            );
        }
    }

    public record BusinessContext(
        String supplierCode,
        String supplierName,
        String contractCode,
        String contractName,
        String purchaseOrderCode,
        String purchaseOrderName,
        String departmentName,
        String responsibleName,
        String acceptanceMode,
        BigDecimal orderedQuantity,
        BigDecimal acceptedQuantity,
        BigDecimal rejectedQuantity,
        BigDecimal remainingQuantity,
        String uomCode,
        BigDecimal orderedAmount,
        BigDecimal acceptedAmount,
        BigDecimal rejectedAmount,
        BigDecimal remainingAmount,
        String currencyCode,
        String uomShortName
    ) {
        public BusinessContext(String supplierCode,
        String supplierName,
        String contractCode,
        String contractName,
        String purchaseOrderCode,
        String purchaseOrderName,
        String departmentName,
        String responsibleName,
        String acceptanceMode,
        BigDecimal orderedQuantity,
        BigDecimal acceptedQuantity,
        BigDecimal rejectedQuantity,
        BigDecimal remainingQuantity,
        String uomCode,
        BigDecimal orderedAmount,
        BigDecimal acceptedAmount,
        BigDecimal rejectedAmount,
        BigDecimal remainingAmount,
        String currencyCode) {
            this(supplierCode, supplierName, contractCode, contractName, purchaseOrderCode, purchaseOrderName, departmentName, responsibleName, acceptanceMode, orderedQuantity, acceptedQuantity, rejectedQuantity, remainingQuantity, uomCode, orderedAmount, acceptedAmount, rejectedAmount, remainingAmount, currencyCode, null);
        }
        public BusinessContext withUnitShortName(String value) {
            return new BusinessContext(supplierCode, supplierName, contractCode, contractName, purchaseOrderCode, purchaseOrderName, departmentName, responsibleName, acceptanceMode, orderedQuantity, acceptedQuantity, rejectedQuantity, remainingQuantity, uomCode, orderedAmount, acceptedAmount, rejectedAmount, remainingAmount, currencyCode, value);
        }

    }

    public record WorkspaceAction(
        String code,
        String targetType,
        UUID targetId,
        Long targetVersion,
        boolean primary,
        boolean enabled,
        boolean reasonRequired,
        boolean idempotencyRequired,
        String disabledReason
    ) {
    }

    public record WorkspaceBlocker(
        String code,
        String severity,
        String message,
        String owner,
        String resolution
    ) {
    }

    public record PaymentReadinessCheck(
        String code,
        boolean passed,
        String severity,
        String message,
        String resolution
    ) {
    }

    public record ServiceOrderItem(
        ServiceOrderResponse order,
        String supplierName,
        String contractCode,
        String purchaseOrderCode,
        List<WorkspaceAction> allowedActions
    ) {
    }

    public record ExecutionItem(
        ServiceExecutionResponse execution,
        String responsibleName,
        boolean overdue,
        List<WorkspaceAction> allowedActions
    ) {
    }

    public record ApprovalContext(
        String moduleKey,
        ApprovalInstanceResponse instance,
        List<ApprovalHistoryResponse> history
    ) {
    }

    public record AcceptanceActItem(
        ServiceAcceptanceActResponse act,
        List<AttachmentDto> evidence,
        EvidenceTarget evidenceTarget,
        List<PaymentReadinessCheck> paymentReadiness,
        List<ApprovalContext> approvals,
        List<WorkspaceAction> allowedActions
    ) {
    }

    public record ClaimItem(
        ServiceClaimResponse claim,
        String supplierName,
        List<AttachmentDto> evidence,
        EvidenceTarget evidenceTarget,
        List<WorkspaceAction> allowedActions
    ) {
    }

    public record RemarkItem(
        UUID id,
        String severity,
        String description,
        LocalDate dueDate,
        UUID assignedTo,
        String assignedToName,
        String status,
        LocalDateTime createdAt
    ) {
    }

    public record IntegrationImpactItem(
        UUID id,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        String status,
        Integer retryCount,
        LocalDateTime nextRetryAt,
        String externalId,
        String lastError,
        LocalDateTime createdAt,
        Long version,
        WorkspaceAction retryAction
    ) {
    }

    public record AuditTimelineItem(
        UUID id,
        String entityType,
        UUID entityId,
        String action,
        String actor,
        String details,
        LocalDateTime occurredAt
    ) {
    }

    public record ProcessStep(
        String key,
        String status,
        boolean completed,
        boolean current,
        LocalDateTime occurredAt
    ) {
    }

    public record SectionAvailability(
        boolean available,
        String reason
    ) {
    }

    public record EvidenceTarget(
        String entityType,
        UUID entityId,
        String attachmentType,
        List<String> allowedAttachmentTypes,
        boolean canUpload,
        String disabledReason
    ) {
    }

    public record MilestoneItem(
        UUID id,
        String code,
        String title,
        String status,
        LocalDate dueDate,
        String acceptanceMode,
        BigDecimal orderedQuantity,
        BigDecimal acceptedQuantity,
        BigDecimal rejectedQuantity,
        BigDecimal remainingQuantity,
        String uomCode,
        BigDecimal orderedAmount,
        BigDecimal acceptedAmount,
        BigDecimal rejectedAmount,
        BigDecimal remainingAmount,
        String currencyCode,
        String uomShortName
    ) {
        public MilestoneItem(UUID id,
        String code,
        String title,
        String status,
        LocalDate dueDate,
        String acceptanceMode,
        BigDecimal orderedQuantity,
        BigDecimal acceptedQuantity,
        BigDecimal rejectedQuantity,
        BigDecimal remainingQuantity,
        String uomCode,
        BigDecimal orderedAmount,
        BigDecimal acceptedAmount,
        BigDecimal rejectedAmount,
        BigDecimal remainingAmount,
        String currencyCode) {
            this(id, code, title, status, dueDate, acceptanceMode, orderedQuantity, acceptedQuantity, rejectedQuantity, remainingQuantity, uomCode, orderedAmount, acceptedAmount, rejectedAmount, remainingAmount, currencyCode, null);
        }
        public MilestoneItem withUnitShortName(String value) {
            return new MilestoneItem(id, code, title, status, dueDate, acceptanceMode, orderedQuantity, acceptedQuantity, rejectedQuantity, remainingQuantity, uomCode, orderedAmount, acceptedAmount, rejectedAmount, remainingAmount, currencyCode, value);
        }

    }

    public record WorkCompletionActItem(
        WorkCompletionActResponse act,
        List<AttachmentDto> evidence,
        EvidenceTarget evidenceTarget,
        List<PaymentReadinessCheck> paymentReadiness,
        List<ApprovalContext> approvals,
        List<WorkspaceAction> allowedActions
    ) {
    }

    public record Workspace(
        ServiceRequestResponse request,
        BusinessContext context,
        String nextAction,
        List<WorkspaceBlocker> blockers,
        List<WorkspaceAction> allowedActions,
        List<ApprovalContext> requestApprovals,
        List<ServiceOrderItem> orders,
        List<ExecutionItem> executions,
        List<AcceptanceActItem> acts,
        List<ClaimItem> claims,
        List<RemarkItem> remarks,
        List<ServiceReviewHistoryResponse> reviewTimeline,
        List<AttachmentDto> requestEvidence,
        List<IntegrationImpactItem> integrationEvents,
        List<AuditTimelineItem> auditTimeline,
        List<String> loadErrors,
        UUID caseId,
        String originType,
        UUID originId,
        String code,
        String title,
        String status,
        String phase,
        Long version,
        boolean completed,
        List<ProcessStep> processSteps,
        List<MilestoneItem> milestones,
        List<WorkCompletionActItem> workActs,
        Map<String, SectionAvailability> sectionAvailability,
        EvidenceTarget evidenceTarget
    ) {
        public Workspace(
            ServiceRequestResponse request,
            BusinessContext context,
            String nextAction,
            List<WorkspaceBlocker> blockers,
            List<WorkspaceAction> allowedActions,
            List<ApprovalContext> requestApprovals,
            List<ServiceOrderItem> orders,
            List<ExecutionItem> executions,
            List<AcceptanceActItem> acts,
            List<ClaimItem> claims,
            List<RemarkItem> remarks,
            List<ServiceReviewHistoryResponse> reviewTimeline,
            List<AttachmentDto> requestEvidence,
            List<IntegrationImpactItem> integrationEvents,
            List<AuditTimelineItem> auditTimeline,
            List<String> loadErrors
        ) {
            this(
                request, context, nextAction, blockers, allowedActions, requestApprovals, orders, executions,
                acts, claims, remarks, reviewTimeline, requestEvidence, integrationEvents, auditTimeline, loadErrors,
                request == null ? null : request.getId(), "SERVICE_REQUEST", request == null ? null : request.getId(),
                request == null ? null : request.getCode(), request == null ? null : request.getName(),
                request == null ? null : request.getStatus(), null, request == null ? null : request.getVersion(),
                request != null && List.of("CLOSED", "CANCELLED").contains(request.getStatus()),
                List.of(), List.of(), List.of(), Map.of(),
                request == null ? null : new EvidenceTarget(
                    "SERVICE_REQUEST", request.getId(), "SCOPE_OF_WORK",
                    List.of("SCOPE_OF_WORK", "SPECIFICATION", "OTHER"), false, "Use canonical workspace permissions"
                )
            );
        }
    }
}
