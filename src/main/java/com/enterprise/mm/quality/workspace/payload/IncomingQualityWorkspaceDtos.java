package com.enterprise.mm.quality.workspace.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class IncomingQualityWorkspaceDtos {
    private IncomingQualityWorkspaceDtos() {
    }

    public enum QueueKey {
        OPEN,
        AWAITING_QUARANTINE,
        AWAITING_SAMPLING,
        LAB_PENDING,
        DECISION_REQUIRED,
        OVERDUE,
        COMPLETED
    }

    public record QueueFilter(
        QueueKey queue,
        String search,
        UUID warehouseId,
        UUID supplierId
    ) {
        public QueueFilter normalized() {
            String normalizedSearch = search == null || search.isBlank()
                ? null
                : search.trim().toLowerCase(Locale.ROOT);
            return new QueueFilter(
                queue == null ? QueueKey.OPEN : queue,
                normalizedSearch,
                warehouseId,
                supplierId
            );
        }
    }

    public record QueueSummary(QueueKey key, long count) {
    }

    public record EntityRef(UUID id, String code, String displayName, String status) {
    }

    public record InspectionListItem(
        UUID id,
        String inspectionNumber,
        String displayName,
        String status,
        Long version,
        EntityRef supplier,
        EntityRef purchaseOrder,
        EntityRef material,
        EntityRef warehouse,
        EntityRef storageLocation,
        EntityRef stockBatch,
        BigDecimal receivedQuantity,
        BigDecimal quarantinedQuantity,
        UUID inspectorEmployeeId,
        LocalDate dueDate,
        boolean overdue,
        String blockerCode,
        String blockerMessage,
        String nextAction,
        List<String> allowedActionCodes
    ) {
    }

    public record InspectionListResponse(
        QueueKey queueKey,
        QueueFilter appliedFilters,
        long totalElements,
        List<InspectionListItem> items,
        int page,
        int size,
        int totalPages,
        LocalDateTime generatedAt,
        String dataScope
    ) {
    }

    public record InspectionHeader(
        UUID id,
        String inspectionNumber,
        String displayName,
        String status,
        Long version,
        String inspectionType,
        EntityRef supplier,
        EntityRef purchaseOrder,
        EntityRef purchaseOrderItem,
        EntityRef stockMovement,
        EntityRef material,
        EntityRef warehouse,
        EntityRef storageLocation,
        EntityRef stockBatch,
        EntityRef inspector,
        LocalDate inspectionDate,
        LocalDate dueDate,
        boolean overdue,
        String description,
        String conclusion,
        LocalDateTime quarantineStartedAt,
        LocalDateTime quarantineReleasedAt,
        LocalDateTime inspectionStartedAt,
        LocalDateTime completedAt,
        String nextAction
    ) {
    }

    public record QuantityContext(
        BigDecimal receivedQuantity,
        BigDecimal sampleQuantity,
        BigDecimal acceptedQuantity,
        BigDecimal rejectedQuantity,
        BigDecimal quarantinedQuantity,
        BigDecimal remainingQuantity
    ) {
    }

    public record StockImpact(
        UUID balanceId,
        BigDecimal onHandQuantity,
        BigDecimal availableQuantity,
        BigDecimal reservedQuantity,
        BigDecimal quarantineQuantity,
        BigDecimal totalValue,
        LocalDateTime balanceAt
    ) {
    }

    public record QuarantineItem(
        UUID id,
        String quarantineNumber,
        String displayName,
        String status,
        Long version,
        BigDecimal quarantinedQuantity,
        BigDecimal releasedQuantity,
        BigDecimal rejectedQuantity,
        BigDecimal remainingQuantity,
        String disposition,
        String quarantineReason,
        EntityRef nonconformance,
        EntityRef responsibleEmployee,
        LocalDateTime quarantineStartedAt,
        LocalDateTime releasedAt,
        LocalDateTime rejectedAt,
        LocalDateTime closedAt
    ) {
    }

    public record LaboratoryItem(
        UUID id,
        String sampleNumber,
        String displayName,
        String status,
        Long version,
        String testType,
        String testMethod,
        String specification,
        String measuredValue,
        String result,
        boolean passed,
        LocalDateTime requestedAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        UUID labTechnicianEmployeeId,
        UUID protocolAttachmentId,
        String limsSampleId,
        String limsStatus,
        LocalDateTime limsRequestedAt,
        LocalDateTime limsResultReceivedAt
    ) {
    }

    public record NonconformanceItem(
        UUID id,
        String code,
        String displayName,
        String status,
        Long version,
        String severity,
        String nonconformanceType,
        BigDecimal detectedQuantity,
        String defectDescription,
        String rootCause,
        String disposition,
        boolean capaRequired,
        EntityRef correctiveAction,
        EntityRef claim,
        LocalDateTime detectedAt,
        LocalDateTime closedAt
    ) {
    }

    public record ClaimItem(
        UUID id,
        String claimNumber,
        String displayName,
        String status,
        Long version,
        LocalDate claimDate,
        BigDecimal claimAmount,
        String currencyCode,
        String requestedAction,
        String supplierResponse,
        LocalDate responseDueDate,
        EntityRef correctiveAction,
        LocalDateTime sentAt,
        LocalDateTime acknowledgedAt,
        LocalDateTime closedAt
    ) {
    }

    public record SupplierImpact(
        EntityRef supplier,
        BigDecimal supplierRating,
        String supplierCategory,
        UUID evaluationId,
        String evaluationStatus,
        String evaluationSource,
        BigDecimal qualityScore,
        BigDecimal totalScore,
        BigDecimal acceptedQuantity,
        BigDecimal rejectedQuantity,
        LocalDateTime evaluatedAt
    ) {
    }

    public record EvidenceItem(
        UUID id,
        String attachmentType,
        String originalFileName,
        String contentType,
        Long sizeBytes,
        LocalDateTime uploadedAt,
        boolean downloadable,
        boolean previewable
    ) {
    }

    public record AuditItem(
        UUID id,
        String entityType,
        UUID entityId,
        String action,
        String username,
        String details,
        LocalDateTime occurredAt
    ) {
    }

    public record IntegrationItem(
        UUID id,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        String status,
        Integer retryCount,
        LocalDateTime nextRetryAt,
        LocalDateTime lastAttemptAt,
        String externalId,
        String lastError,
        LocalDateTime createdAt
    ) {
    }

    public record WorkspaceBlocker(
        String code,
        String message,
        String severity,
        String ownerRole,
        String nextAction
    ) {
    }

    public record WorkspaceAction(
        String code,
        boolean primary,
        boolean enabled,
        String disabledReason,
        List<String> requiredRoles,
        List<String> requiredFields,
        String confirmationImpact,
        boolean irreversible
    ) {
    }

    public record InspectionDetail(
        InspectionHeader header,
        QuantityContext quantities,
        StockImpact stockImpact,
        List<QuarantineItem> quarantine,
        List<LaboratoryItem> laboratoryTests,
        List<NonconformanceItem> nonconformances,
        List<ClaimItem> claims,
        SupplierImpact supplierImpact,
        List<EvidenceItem> evidence,
        List<AuditItem> auditTimeline,
        List<IntegrationItem> integrationImpact,
        List<WorkspaceBlocker> blockers,
        List<WorkspaceAction> allowedActions,
        LocalDateTime generatedAt,
        String dataScope
    ) {
    }
}
