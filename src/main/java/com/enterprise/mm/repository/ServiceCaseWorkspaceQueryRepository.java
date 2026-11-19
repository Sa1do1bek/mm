package com.enterprise.mm.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Database-backed canonical read projection for service requests, request-less
 * PO service orders, and PO works lines. Count and list deliberately accept the
 * same filter value so callers cannot accidentally diverge queue predicates.
 */
public interface ServiceCaseWorkspaceQueryRepository {
    enum OriginType {
        SERVICE_REQUEST,
        PO_SERVICE,
        PO_WORKS
    }

    enum Scope {
        MY,
        TEAM,
        ALL
    }

    record CaseFilter(
        String queue,
        OriginType originType,
        Scope scope,
        UUID currentUserId,
        UUID teamDepartmentId,
        UUID ownerId,
        UUID supplierId,
        UUID departmentId,
        UUID contractId,
        String currencyCode,
        String category,
        String exposureMetric,
        String agingBucket,
        LocalDate integrationDay,
        Boolean hasOpenClaims,
        Boolean hasRework,
        List<String> statuses,
        Boolean overdue,
        String search
    ) {
        public CaseFilter {
            queue = normalize(queue, "ALL");
            scope = scope == null ? Scope.ALL : scope;
            statuses = statuses == null
                ? List.of()
                : statuses.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> normalize(value, null))
                    .distinct()
                    .toList();
            search = search == null || search.isBlank()
                ? null
                : search.trim().toLowerCase(Locale.ROOT);
            currencyCode = normalize(currencyCode, null);
            category = normalize(category, null);
            exposureMetric = normalize(exposureMetric, null);
            agingBucket = normalize(agingBucket, null);
        }

        public static CaseFilter all() {
            return new CaseFilter(
                "ALL", null, Scope.ALL, null, null, null, null, null, null, null, null, null, null, null, null, null,
                List.of(), null, null
            );
        }

        public CaseFilter withQueue(String value) {
            return new CaseFilter(value, originType, scope, currentUserId, teamDepartmentId, ownerId, supplierId,
                departmentId, contractId, currencyCode, category, exposureMetric, agingBucket, integrationDay, hasOpenClaims, hasRework, statuses, overdue, search);
        }

        public CaseFilter withOriginType(OriginType value) {
            return new CaseFilter(queue, value, scope, currentUserId, teamDepartmentId, ownerId, supplierId,
                departmentId, contractId, currencyCode, category, exposureMetric, agingBucket, integrationDay, hasOpenClaims, hasRework, statuses, overdue, search);
        }

        public CaseFilter withScope(Scope value, UUID userId, UUID departmentId) {
            return new CaseFilter(queue, originType, value, userId, departmentId, ownerId, supplierId,
                this.departmentId, contractId, currencyCode, category, exposureMetric, agingBucket, integrationDay, hasOpenClaims, hasRework, statuses, overdue, search);
        }

        public CaseFilter withOwnerId(UUID value) {
            return new CaseFilter(queue, originType, scope, currentUserId, teamDepartmentId, value, supplierId,
                departmentId, contractId, currencyCode, category, exposureMetric, agingBucket, integrationDay, hasOpenClaims, hasRework, statuses, overdue, search);
        }

        public CaseFilter withSupplierId(UUID value) {
            return new CaseFilter(queue, originType, scope, currentUserId, teamDepartmentId, ownerId, value,
                departmentId, contractId, currencyCode, category, exposureMetric, agingBucket, integrationDay, hasOpenClaims, hasRework, statuses, overdue, search);
        }

        public CaseFilter withDepartmentId(UUID value) {
            return new CaseFilter(queue, originType, scope, currentUserId, teamDepartmentId, ownerId, supplierId,
                value, contractId, currencyCode, category, exposureMetric, agingBucket, integrationDay, hasOpenClaims, hasRework, statuses, overdue, search);
        }

        public CaseFilter withContractId(UUID value) {
            return new CaseFilter(queue, originType, scope, currentUserId, teamDepartmentId, ownerId, supplierId,
                departmentId, value, currencyCode, category, exposureMetric, agingBucket, integrationDay, hasOpenClaims, hasRework, statuses, overdue, search);
        }

        public CaseFilter withCurrencyCode(String value) {
            return new CaseFilter(queue, originType, scope, currentUserId, teamDepartmentId, ownerId, supplierId,
                departmentId, contractId, value, category, exposureMetric, agingBucket, integrationDay, hasOpenClaims, hasRework, statuses, overdue, search);
        }

        public CaseFilter withCategory(String value) {
            return new CaseFilter(queue, originType, scope, currentUserId, teamDepartmentId, ownerId, supplierId,
                departmentId, contractId, currencyCode, value, exposureMetric, agingBucket, integrationDay, hasOpenClaims, hasRework, statuses, overdue, search);
        }

        public CaseFilter withExposureMetric(String value) {
            return new CaseFilter(queue, originType, scope, currentUserId, teamDepartmentId, ownerId, supplierId,
                departmentId, contractId, currencyCode, category, value, agingBucket, integrationDay, hasOpenClaims, hasRework, statuses, overdue, search);
        }

        public CaseFilter withAgingBucket(String value) {
            return new CaseFilter(queue, originType, scope, currentUserId, teamDepartmentId, ownerId, supplierId,
                departmentId, contractId, currencyCode, category, exposureMetric, value, integrationDay, hasOpenClaims, hasRework, statuses, overdue, search);
        }

        public CaseFilter withIntegrationDay(LocalDate value) {
            return new CaseFilter(queue, originType, scope, currentUserId, teamDepartmentId, ownerId, supplierId,
                departmentId, contractId, currencyCode, category, exposureMetric, agingBucket, value, hasOpenClaims, hasRework, statuses, overdue, search);
        }

        public CaseFilter withHasOpenClaims(Boolean value) {
            return new CaseFilter(queue, originType, scope, currentUserId, teamDepartmentId, ownerId, supplierId,
                departmentId, contractId, currencyCode, category, exposureMetric, agingBucket, integrationDay, value, hasRework, statuses, overdue, search);
        }

        public CaseFilter withHasRework(Boolean value) {
            return new CaseFilter(queue, originType, scope, currentUserId, teamDepartmentId, ownerId, supplierId,
                departmentId, contractId, currencyCode, category, exposureMetric, agingBucket, integrationDay, hasOpenClaims, value, statuses, overdue, search);
        }

        public CaseFilter withStatuses(List<String> value) {
            return new CaseFilter(queue, originType, scope, currentUserId, teamDepartmentId, ownerId, supplierId,
                departmentId, contractId, currencyCode, category, exposureMetric, agingBucket, integrationDay, hasOpenClaims, hasRework, value, overdue, search);
        }

        public CaseFilter withOverdue(Boolean value) {
            return new CaseFilter(queue, originType, scope, currentUserId, teamDepartmentId, ownerId, supplierId,
                departmentId, contractId, currencyCode, category, exposureMetric, agingBucket, integrationDay, hasOpenClaims, hasRework, statuses, value, search);
        }

        public CaseFilter withSearch(String value) {
            return new CaseFilter(queue, originType, scope, currentUserId, teamDepartmentId, ownerId, supplierId,
                departmentId, contractId, currencyCode, category, exposureMetric, agingBucket, integrationDay, hasOpenClaims, hasRework, statuses, overdue, value);
        }

        private static String normalize(String value, String fallback) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        }
    }

    record CaseRow(
        UUID caseId,
        OriginType originType,
        UUID originId,
        UUID requestId,
        UUID serviceOrderId,
        UUID purchaseOrderId,
        UUID purchaseOrderItemId,
        UUID latestActId,
        String code,
        String title,
        String status,
        String phase,
        UUID supplierId,
        String supplierName,
        UUID contractId,
        String contractCode,
        UUID responsibleId,
        String responsibleName,
        UUID departmentId,
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
        LocalDateTime createdAt,
        LocalDateTime completedAt
    ) {
    }

    record WorkActRow(
        UUID id,
        String code,
        String name,
        String status,
        Long version,
        UUID purchaseOrderId,
        UUID purchaseOrderItemId,
        UUID contractId,
        String workLocation,
        String milestone,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal completedQuantity,
        BigDecimal amount,
        String inspectionResult,
        LocalDateTime acceptedAt,
        String acceptedBy,
        String commissionNotes,
        String remarks,
        LocalDateTime documentReviewedAt,
        LocalDateTime financeReviewedAt,
        LocalDateTime sentToPaymentAt,
        LocalDateTime paidAt,
        LocalDateTime contractConsumedAt,
        String paymentReference,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {
    }

    record ManagementBucket(
        String dimension,
        String key,
        String label,
        long caseCount,
        BigDecimal amount,
        String currencyCode,
        long currencyCount
    ) {
    }

    Page<CaseRow> findCases(CaseFilter filter, Pageable pageable);

    long count(CaseFilter filter);

    Optional<CaseRow> findCase(UUID caseId, CaseFilter visibilityFilter);

    Optional<CaseRow> findCaseByAggregateId(UUID aggregateId, CaseFilter visibilityFilter);

    List<ManagementBucket> managementBuckets(CaseFilter visibilityFilter);

    List<WorkActRow> findWorkActs(UUID purchaseOrderItemId);
}
