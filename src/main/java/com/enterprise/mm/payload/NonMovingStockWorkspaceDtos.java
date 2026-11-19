package com.enterprise.mm.payload;

import com.enterprise.mm.payload.ApprovalDtos.ApprovalHistoryResponse;
import java.math.BigDecimal;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalInstanceResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class NonMovingStockWorkspaceDtos {
    private NonMovingStockWorkspaceDtos() {
    }

    public record WorkspaceSummary(
        long detectedPositions,
        long activeCases,
        long overdueCases,
        long commissionReviewCases,
        long approvalPendingCases,
        long executionBlockedCases,
        long failedIntegrations,
        long closedPeriodCases,
        BigDecimal valueAtRisk,
        String valueCurrencyCode,
        String valueHealth,
        String valueHealthMessage
    ) {
    }

    public enum WorkspaceMode {
        CASES,
        DETECTED
    }

    public record SavedViewFilter(
        String search,
        List<String> statuses,
        UUID warehouseId,
        UUID responsibleUserId,
        List<String> decisionTypes,
        Boolean overdueOnly,
        LocalDate dateFrom,
        LocalDate dateTo,
        UUID materialGroupId,
        BigDecimal minBookValue,
        BigDecimal maxBookValue,
        List<String> executionStatuses,
        List<String> blockerTypes,
        List<String> integrationStatuses,
        List<String> exclusionResults,
        Boolean activeCaseOnly
    ) {
        public static SavedViewFilter empty() {
            return new SavedViewFilter(
                null, List.of(), null, null, List.of(), false, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), false
            );
        }

        public SavedViewFilter normalized() {
            return new SavedViewFilter(
                normalizeText(search), normalizeCodes(statuses), warehouseId, responsibleUserId,
                normalizeCodes(decisionTypes), Boolean.TRUE.equals(overdueOnly), dateFrom, dateTo,
                materialGroupId, minBookValue, maxBookValue, normalizeCodes(executionStatuses),
                normalizeCodes(blockerTypes), normalizeCodes(integrationStatuses),
                normalizeCodes(exclusionResults), Boolean.TRUE.equals(activeCaseOnly)
            );
        }

        private static String normalizeText(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }

        private static List<String> normalizeCodes(List<String> values) {
            return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .sorted()
                .toList();
        }
    }

    public record SavedViewRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull WorkspaceMode mode,
        @NotNull @Valid SavedViewFilter filters,
        boolean defaultView
    ) {
    }

    public record SavedViewResponse(
        UUID id,
        String name,
        WorkspaceMode mode,
        SavedViewFilter filters,
        boolean defaultView,
        long version
    ) {
    }

    public record SelectorOption(UUID id, String code, String name, String displayName, String status) {
    }

    public record WorkspaceAction(
        String code,
        boolean primary,
        boolean reasonRequired,
        boolean futureDateRequired,
        boolean idempotencyRequired
    ) {
    }

    public record WorkspaceBlocker(String code, String message) {
    }

    public record EvidenceRequirement(
        String code,
        List<String> decisionTypes,
        List<String> acceptedTypes,
        boolean satisfied,
        String message
    ) {
    }

    public record WorkspaceReadiness(
        boolean closureReady,
        List<WorkspaceBlocker> blockers,
        List<EvidenceRequirement> evidenceRequirements
    ) {
    }

    public record EvidenceItem(
        UUID id,
        String fileName,
        String attachmentType,
        Long sizeBytes,
        LocalDateTime uploadedAt,
        boolean downloadable,
        boolean previewable
    ) {
    }

    public record IntegrationImpactItem(
        UUID id,
        String eventType,
        String status,
        Integer retryCount,
        LocalDateTime nextRetryAt,
        String externalId,
        String lastError,
        LocalDateTime createdAt
    ) {
    }

    public record AuditTimelineItem(UUID id, String action, String actor, String details, LocalDateTime occurredAt) {
    }

    public record CaseWorkspace(
        NonMovingStockCaseResponse stockCase,
        String nextAction,
        List<WorkspaceBlocker> blockers,
        List<WorkspaceAction> allowedActions,
        WorkspaceReadiness readiness,
        List<EvidenceItem> evidence,
        List<IntegrationImpactItem> integrationEvents,
        List<AuditTimelineItem> auditTimeline,
        ApprovalInstanceResponse approval,
        List<ApprovalHistoryResponse> approvalHistory,
        List<String> loadErrors
    ) {
    }
}
