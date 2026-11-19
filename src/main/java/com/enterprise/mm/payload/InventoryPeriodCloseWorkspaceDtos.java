package com.enterprise.mm.payload;

import java.math.BigDecimal;
import com.enterprise.mm.enums.ValuationMethod;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class InventoryPeriodCloseWorkspaceDtos {
    private InventoryPeriodCloseWorkspaceDtos() {
    }

    public record Summary(
        long activePeriods,
        long readyPeriods,
        long blockedPeriods,
        long openMovements,
        BigDecimal fiDifference,
        long activeValuationJobs,
        long failedValuationJobs,
        long failedFinanceIntegrations
    ) {
    }

    public record WarehouseContext(UUID id, String code, String name) {
    }

    public record ReadinessCheck(
        String code,
        String severity,
        boolean passed,
        long count,
        String message,
        String owner,
        String resolution
    ) {
    }

    public record WorkspaceAction(
        String code,
        boolean primary,
        boolean enabled,
        boolean reasonRequired,
        String disabledReason
    ) {
    }

    public record CloseAuthorization(
        String mode,
        String status,
        List<String> requiredRoles,
        String message
    ) {
    }

    public record ValuationJobItem(
        UUID id,
        UUID warehouseId,
        ValuationMethod valuationMethod,
        String requestedBy,
        String status,
        String phase,
        BigDecimal progressPercent,
        int totalItems,
        int processedItems,
        int failedItems,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt
    ) {
    }

    public record IntegrationImpactItem(
        UUID id,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        String status,
        int retryCount,
        LocalDateTime nextRetryAt,
        String lastError,
        LocalDateTime createdAt
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

    public record Workspace(
        InventoryPeriodCloseResponse periodClose,
        WarehouseContext warehouse,
        List<ReadinessCheck> checks,
        long blockerCount,
        long warningCount,
        boolean readyToClose,
        String nextAction,
        InventoryAccountingReconciliationResponse reconciliation,
        List<InventoryValuationReconciliationIssueResponse> valuationIssues,
        List<ValuationJobItem> valuationJobs,
        List<IntegrationImpactItem> integrationEvents,
        List<AuditTimelineItem> auditTimeline,
        CloseAuthorization closeAuthorization,
        List<WorkspaceAction> allowedActions,
        List<String> loadErrors
    ) {
    }
}
