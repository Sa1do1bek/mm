package com.enterprise.mm.supplier.governance.payload;

import com.enterprise.mm.supplier.governance.enums.SupplierGovernanceQueueKey;
import com.enterprise.mm.supplier.governance.readiness.SupplierEvidenceChecklistItem;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.SupplierReadinessSummary;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierComplianceSummary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class SupplierGovernanceDtos {
    private SupplierGovernanceDtos() {
    }

    public record SupplierGovernanceFilter(
        SupplierGovernanceQueueKey queue,
        String search,
        Integer horizonDays,
        UUID purchaseOrderId
    ) {
        public SupplierGovernanceFilter normalized() {
            String normalizedSearch = search == null || search.isBlank()
                ? null
                : search.trim().toLowerCase(Locale.ROOT);
            int normalizedHorizon = horizonDays == null || horizonDays <= 0
                ? 30
                : Math.min(horizonDays, 365);
            SupplierGovernanceQueueKey normalizedQueue = queue == null
                ? SupplierGovernanceQueueKey.ACCREDITATION_REVIEW
                : queue;
            return new SupplierGovernanceFilter(
                normalizedQueue, normalizedSearch, normalizedHorizon, purchaseOrderId);
        }
    }

    public record EntityRef(UUID id, String code, String displayName, String status) {
    }

    public record QueueDefinition(
        SupplierGovernanceQueueKey key,
        String titleKey,
        long count,
        SupplierGovernanceFilter appliedFilters
    ) {
    }

    public record GovernanceOverview(
        long accreditationReview,
        long expiringEvidence,
        long complianceRisk,
        long openCapa,
        long receivingBlocked,
        LocalDateTime generatedAt
    ) {
    }

    public record SupplierGovernanceCaseRow(
        EntityRef supplier,
        String qualificationStatus,
        BigDecimal riskScore,
        String sourceType,
        UUID sourceId,
        String sourceCode,
        String sourceStatus,
        String overallReadiness,
        int readinessPercent,
        String currentStage,
        String nextAction,
        String blockerCode,
        String blockerMessage,
        String ownerRole,
        LocalDate dueDate,
        long approvedScopeCount,
        long pendingScopeCount,
        long affectedPurchaseOrderCount,
        Long version,
        String eligibilityStatus
    ) {
    }

    public record QueueResponse(
        SupplierGovernanceQueueKey queueKey,
        SupplierGovernanceFilter appliedFilters,
        long totalElements,
        List<SupplierGovernanceCaseRow> items,
        int page,
        int size,
        int totalPages,
        LocalDateTime generatedAt,
        String dataScope
    ) {
    }

    public record SupplierHeader(
        UUID id,
        String code,
        String displayName,
        String legalName,
        String shortName,
        String relationshipType,
        String supplierType,
        UUID ownerEmployeeId,
        String ownerDisplayName,
        String primaryContactName,
        String countryCode,
        String city,
        String legalForm,
        String status,
        Long version,
        String category,
        BigDecimal rating,
        String taxId,
        String blockedReason
    ) {
    }

    public record QualificationContext(
        String status,
        String blockedReason,
        String blockedBy,
        LocalDateTime blockedAt,
        LocalDate expiresAt,
        List<RestrictionContext> restrictions
    ) {
    }

    public record RestrictionContext(String type, String value, String reason) {
    }

    public record AccreditationApplicationContext(
        UUID id,
        String code,
        String displayName,
        String status,
        String accreditationType,
        String requestedScope,
        List<UUID> requestedMaterialGroupIds,
        LocalDateTime submittedAt,
        LocalDate validFrom,
        LocalDate validTo,
        BigDecimal riskScore,
        String complianceRecommendation,
        String reviewComment,
        Long version,
        UUID approvalInstanceId,
        String approvalStatus
    ) {
    }

    public record AccreditationDecisionContext(
        UUID id,
        UUID applicationId,
        String code,
        String decision,
        String status,
        String approvedScope,
        List<UUID> allowedMaterialGroupIds,
        LocalDate validFrom,
        LocalDate validTo,
        String conditions,
        String commissionProtocolNo,
        LocalDateTime decidedAt
    ) {
    }

    public record ScopeMatrixRow(
        EntityRef materialGroup,
        boolean eligible,
        String reasonCode,
        String reason,
        UUID applicationId,
        UUID decisionId,
        String documentType,
        String documentCode,
        LocalDate validTo,
        String conditions
    ) {
    }

    public record EvidenceContext(
        UUID id,
        UUID applicationId,
        String code,
        String displayName,
        String documentType,
        UUID attachmentId,
        String status,
        LocalDate validFrom,
        LocalDate validTo,
        String expiryUrgency,
        String verifiedBy,
        LocalDateTime verifiedAt,
        Long version
    ) {
    }

    public record LicenseContext(
        UUID id,
        String code,
        String displayName,
        String status,
        String licenseNumber,
        String licenseType,
        LocalDate validTo,
        boolean renewalRequired,
        LocalDate revokedAt,
        String revocationReason
    ) {
    }

    public record KycContext(
        UUID id,
        String code,
        String displayName,
        String status,
        String checkType,
        String riskLevel,
        BigDecimal score,
        LocalDateTime checkedAt,
        LocalDate validUntil,
        LocalDate nextReviewDate,
        boolean remediationRequired,
        String findings
    ) {
    }

    public record CapaContext(
        UUID id,
        String code,
        String displayName,
        String status,
        String sourceType,
        UUID sourceId,
        String issueTitle,
        String severity,
        UUID ownerEmployeeId,
        LocalDate dueDate,
        LocalDateTime submittedAt,
        LocalDateTime verifiedAt,
        LocalDateTime closedAt,
        String verificationResult
    ) {
    }

    public record UnblockRequestContext(
        UUID id,
        String code,
        String status,
        String reason,
        UUID purchaseOrderId,
        UUID requestedBy,
        UUID reviewedBy,
        LocalDateTime reviewedAt,
        String reviewComment,
        UUID decidedBy,
        LocalDateTime decidedAt,
        String decisionComment,
        Long version,
        LocalDateTime createdAt
    ) {
    }

    public record AffectedDocumentContext(
        String documentType,
        UUID id,
        String code,
        String displayName,
        String status,
        String blockerCode,
        String blockerMessage,
        String route
    ) {
    }

    public record GovernanceAction(
        String code,
        String labelKey,
        boolean enabled,
        String disabledReasonCode,
        Map<String, String> disabledReasonParams,
        List<String> requiredRoles,
        boolean commentRequired,
        boolean evidenceRequired,
        UUID applicationId,
        Long version
    ) {
        public GovernanceAction {
            disabledReasonParams = disabledReasonParams == null ? Map.of() : Map.copyOf(disabledReasonParams);
        }
    }

    public record AuditContext(
        UUID id,
        String action,
        String actor,
        String details,
        LocalDateTime occurredAt
    ) {
    }

    public record IntegrationContext(
        UUID id,
        String eventType,
        String status,
        Integer retryCount,
        String lastError,
        LocalDateTime createdAt
    ) {
    }

    public record PerformancePeriod(LocalDate from, LocalDate to, String preset) {
    }

    public record MoneyTotal(String currencyCode, BigDecimal amount) {
    }

    public record MetricDefinition(String code, String unit, String descriptionKey) {
    }

    public record ProcurementPerformance(
        long rfqInvitations,
        long rfqsParticipated,
        long quotesSubmitted,
        long awardsWon,
        BigDecimal winRatePercent,
        List<MoneyTotal> awardedValueByCurrency,
        boolean participationDataAvailable
    ) {
    }

    public record OperationalPerformance(
        BigDecimal onTimeDeliveryPercent,
        BigDecimal qualityScore,
        Long claims,
        Long returnsToSupplier,
        Long evaluatedDeliveries,
        boolean evaluationDataAvailable
    ) {
    }

    public record SupplierPerformanceSummary(
        PerformancePeriod period,
        ProcurementPerformance procurement,
        OperationalPerformance operations,
        List<MetricDefinition> definitions,
        LocalDateTime generatedAt
    ) {
    }

    public record ActivityEventContext(
        UUID id,
        String eventCode,
        String titleKey,
        String actorDisplayName,
        String actorRole,
        String objectType,
        String objectDisplayName,
        String result,
        String summary,
        String route,
        LocalDateTime occurredAt
    ) {
    }

    public record SupplierGovernanceContext(
        SupplierHeader header,
        QualificationContext qualification,
        List<AccreditationApplicationContext> applications,
        List<AccreditationDecisionContext> decisions,
        List<ScopeMatrixRow> scopeMatrix,
        String scopeEligibilityStatus,
        List<EvidenceContext> evidence,
        List<LicenseContext> licenses,
        List<KycContext> kycChecks,
        List<CapaContext> correctiveActions,
        List<UnblockRequestContext> unblockRequests,
        List<AffectedDocumentContext> affectedDocuments,
        List<GovernanceAction> allowedActions,
        List<AuditContext> auditTimeline,
        List<ActivityEventContext> activityTimeline,
        List<IntegrationContext> integrationImpact,
        SupplierComplianceSummary complianceSummary,
        SupplierPerformanceSummary performance,
        SupplierReadinessSummary readiness,
        List<SupplierEvidenceChecklistItem> evidenceChecklist,
        UUID originPurchaseOrderId,
        LocalDateTime generatedAt,
        String dataScope
    ) {
    }
}
