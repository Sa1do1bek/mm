package com.enterprise.mm.warehouse.execution.payload;

import com.enterprise.mm.payload.IssueRequestResponse;
import com.enterprise.mm.payload.MaterialReservationResponse;
import com.enterprise.mm.payload.MaterialReturnResponse;
import com.enterprise.mm.payload.TransferDocumentResponse;
import com.enterprise.mm.service.transfer.TransferProgressService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class WarehouseExecutionDtos {
    private WarehouseExecutionDtos() {
    }

    public enum DocumentType {
        ISSUE_REQUEST("issue-requests"),
        RESERVATION("reservations"),
        TRANSFER("transfers"),
        RETURN("returns");

        private final String path;

        DocumentType(String path) {
            this.path = path;
        }

        public String path() {
            return path;
        }

        public static DocumentType fromPath(String path) {
            for (DocumentType type : values()) {
                if (type.path.equalsIgnoreCase(path)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unsupported warehouse document type: " + path);
        }
    }

    public record QueueFilter(String search, List<String> status, UUID warehouseId, String action) {
        public QueueFilter(String search, List<String> status, UUID warehouseId) { this(search,status,warehouseId,null); }
        public QueueFilter normalized() {
            String normalizedSearch = search == null || search.isBlank() ? null : search.trim();
            List<String> normalizedStatus = status == null
                ? List.of()
                : status.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toUpperCase(Locale.ROOT))
                    .distinct()
                    .sorted()
                    .toList();
            return new QueueFilter(normalizedSearch, normalizedStatus, warehouseId, action == null ? null : action.trim().toLowerCase(Locale.ROOT));
        }
    }

    public record EntityRef(UUID id, String code, String displayName, String status) {
    }

    public record SelectorOption(UUID id, String code, String name, String displayName, String status) {
    }

    public record QueueItem(
        DocumentType documentType,
        EntityRef document,
        EntityRef primaryWarehouse,
        EntityRef secondaryWarehouse,
        String statusLabelKey,
        LocalDate dueDate,
        boolean overdue,
        long lineCount,
        BigDecimal primaryQuantity,
        String quantityLabelKey,
        String nextAction,
        String nextActionLabelKey,
        String ownerRole,
        String blockerCode,
        String blockerMessage,
        Long version,
        List<TransferProgressService.UnitSummary> unitSummaries,
        @Schema(description = "Exact unresolved transfer book value; null for non-transfer items, restricted roles or unreconciled legacy transfers")
        BigDecimal inTransitAmount,
        @Schema(description = "Book currency for transfer amounts", example = "UZS")
        String bookCurrencyCode
    ) {
    }

    public record QueueResponse(
        DocumentType documentType,
        QueueFilter appliedFilters,
        long totalElements,
        List<QueueItem> items,
        int page,
        int size,
        int totalPages,
        LocalDateTime generatedAt,
        String dataScope
    ) {
    }

    public record QueueSummary(
        DocumentType documentType,
        String titleKey,
        long count,
        QueueFilter appliedFilters,
        String route,
        String ownerRole
    ) {
    }

    public record Overview(
        List<QueueSummary> queues,
        LocalDateTime generatedAt,
        String dataScope,
        UUID warehouseId,
        boolean canReceiveFromSupplier,
        boolean canReceiveFromWarehouse
    ) {
    }

    public record WorkspaceBlocker(
        String code,
        String severity,
        String message,
        String ownerRole,
        String resolutionAction,
        String labelKey,
        String resolutionLink,
        Long version
    ) {
        public WorkspaceBlocker(String code, String severity, String message, String ownerRole, String resolutionAction) {
            this(code, severity, message, ownerRole, resolutionAction, null, null, null);
        }
    }

    public record WorkspaceAction(
        String code,
        String labelKey,
        boolean enabled,
        String disabledReason,
        List<String> requiredRoles,
        List<String> requiredFields,
        String confirmationImpact,
        boolean irreversible,
        boolean idempotencyRequired
    ) {
    }

    public record RelatedDocument(
        String documentType,
        UUID id,
        String code,
        String displayName,
        String route
    ) {
    }

    public record AuditTimelineItem(
        UUID id,
        String action,
        String actor,
        String details,
        LocalDateTime occurredAt
    ) {
    }

    public record IntegrationImpactItem(
        UUID id,
        String eventType,
        String status,
        Integer retryCount,
        String lastError,
        LocalDateTime createdAt
    ) {
    }

    public record IssueRequestContext(
        IssueRequestResponse document,
        List<WorkspaceBlocker> blockers,
        List<WorkspaceAction> allowedActions,
        List<RelatedDocument> relatedDocuments,
        List<AuditTimelineItem> auditTimeline,
        List<IntegrationImpactItem> integrationImpact,
        LocalDateTime generatedAt,
        String dataScope
    ) {
    }

    public record ReservationContext(
        MaterialReservationResponse document,
        List<WorkspaceBlocker> blockers,
        List<WorkspaceAction> allowedActions,
        List<RelatedDocument> relatedDocuments,
        List<AuditTimelineItem> auditTimeline,
        List<IntegrationImpactItem> integrationImpact,
        LocalDateTime generatedAt,
        String dataScope
    ) {
    }

    public record TransferContext(
        TransferDocumentResponse document,
        List<WorkspaceBlocker> blockers,
        List<WorkspaceAction> allowedActions,
        List<RelatedDocument> relatedDocuments,
        List<AuditTimelineItem> auditTimeline,
        List<IntegrationImpactItem> integrationImpact,
        LocalDateTime generatedAt,
        String dataScope,
        com.enterprise.mm.payload.TransferReceiptDtos.Context receipt
    ) {
    }

    public record ReturnContext(
        MaterialReturnResponse document,
        List<WorkspaceBlocker> blockers,
        List<WorkspaceAction> allowedActions,
        List<RelatedDocument> relatedDocuments,
        List<AuditTimelineItem> auditTimeline,
        List<IntegrationImpactItem> integrationImpact,
        LocalDateTime generatedAt,
        String dataScope
    ) {
    }
}
