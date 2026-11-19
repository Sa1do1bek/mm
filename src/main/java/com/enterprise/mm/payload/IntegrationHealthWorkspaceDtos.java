package com.enterprise.mm.payload;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class IntegrationHealthWorkspaceDtos {
    private IntegrationHealthWorkspaceDtos() {
    }

    public record Summary(
        long failed,
        long retryScheduled,
        long noTransport,
        long awaitingReconciliation,
        long deadManualDecision,
        long publishedToday
    ) {
    }

    public record QueueDescriptor(
        String key,
        String titleKey,
        long count,
        Map<String, String> appliedFilters
    ) {
    }

    public record BusinessDocumentReference(
        String type,
        UUID id,
        String code,
        String name,
        String moduleKey,
        String route,
        String documentNumber,
        boolean identityAvailable
    ) {
        public BusinessDocumentReference(
            String type,
            UUID id,
            String code,
            String name,
            String moduleKey,
            String route
        ) {
            this(type, id, code, name, moduleKey, route, code, id != null && code != null);
        }
    }

    public record WorkspaceAction(
        String code,
        boolean primary,
        boolean enabled,
        boolean reasonRequired,
        boolean idempotencyRequired,
        String disabledReason
    ) {
    }

    public record SystemContext(
        String sourceSystem,
        String targetSystem,
        String targetName,
        String transport,
        boolean enabled,
        boolean configured,
        boolean mockMode,
        String readiness,
        String remediation
    ) {
    }

    public record EventListItem(
        UUID id,
        String code,
        String eventType,
        String eventMeaning,
        String status,
        String queueKey,
        String businessImpact,
        BusinessDocumentReference businessDocument,
        String owner,
        String runbookCode,
        String sourceSystem,
        String targetSystem,
        Integer retryCount,
        LocalDateTime nextRetryAt,
        LocalDateTime lastAttemptAt,
        LocalDateTime createdAt,
        String lastError,
        String externalId,
        Long version,
        List<WorkspaceAction> allowedActions
    ) {
    }

    public record AttemptTimelineItem(
        UUID id,
        String action,
        String outcome,
        String actor,
        String details,
        LocalDateTime occurredAt
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

    /**
     * UX-LIVE-03: sibling integration events sharing the same business document (aggregateId)
     * so a stalled/failed event's detail view can point the operator at related activity and
     * whether it still needs recovery.
     */
    public record RelatedBusinessDocument(
        UUID eventId,
        String eventType,
        String eventStatus,
        BusinessDocumentReference document,
        String recoveryStatus
    ) {
    }

    public record EventDetail(
        EventListItem event,
        SystemContext system,
        JsonNode redactedRequestMetadata,
        JsonNode responseMetadata,
        List<AttemptTimelineItem> attempts,
        List<AuditTimelineItem> auditTimeline,
        UUID replayOfEventId,
        List<UUID> replayEventIds,
        String manualReason,
        List<RelatedBusinessDocument> relatedDocuments
    ) {
    }

    public record CommandResult(
        UUID eventId,
        String status,
        Long version,
        UUID replayEventId,
        String message,
        String nextStage,
        String nextOwner
    ) {
    }
}
