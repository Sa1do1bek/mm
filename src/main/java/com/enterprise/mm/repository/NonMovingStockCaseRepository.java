package com.enterprise.mm.repository;

import com.enterprise.mm.entity.NonMovingStockCase;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NonMovingStockCaseRepository extends JpaRepository<NonMovingStockCase, UUID> {
    Optional<NonMovingStockCase> findAnyById(UUID id);
    Optional<NonMovingStockCase> findByIdAndIsDeletedFalse(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from NonMovingStockCase e where e.id = :id and e.isDeleted = false")
    Optional<NonMovingStockCase> findByIdAndIsDeletedFalseForUpdate(@Param("id") UUID id);

    @Query("""
        SELECT e
        FROM NonMovingStockCase e
        WHERE
            e.tenantId = :tenantId
            AND (:companyId IS NULL OR e.companyId = :companyId)
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (:searchEmpty = true OR
                 LOWER(COALESCE(e.caseNo, '')) LIKE :searchPattern
                 OR LOWER(COALESCE(e.code, '')) LIKE :searchPattern
                 OR LOWER(COALESCE(e.name, '')) LIKE :searchPattern)
            AND (CAST(:dateFrom AS timestamp) IS NULL OR COALESCE(e.detectedAt, e.createdAt) >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR COALESCE(e.detectedAt, e.createdAt) <= :dateTo)
            AND (:statusEmpty = true OR e.status IN :status)
            AND (:warehouseId IS NULL OR EXISTS (
                SELECT 1 FROM NonMovingStockCaseLine line
                WHERE line.tenantId = e.tenantId
                  AND line.companyId = e.companyId
                  AND line.caseId = e.id
                  AND line.isDeleted = false
                  AND line.warehouseId = :warehouseId
            ))
            AND (:materialIdsEmpty = true OR EXISTS (
                SELECT 1 FROM NonMovingStockCaseLine line
                WHERE line.tenantId = e.tenantId
                  AND line.companyId = e.companyId
                  AND line.caseId = e.id
                  AND line.isDeleted = false
                  AND line.materialId IN :materialIds
            ))
            AND (:responsibleUserId IS NULL OR e.responsibleUserId = :responsibleUserId)
            AND (:overdueOnly = false OR (
                e.reviewDueDate < CURRENT_DATE
                AND e.status NOT IN ('CLOSED', 'CANCELLED')
            ))
            AND (:decisionTypesEmpty = true OR EXISTS (
                SELECT 1 FROM NonMovingStockDecision decision
                WHERE decision.tenantId = e.tenantId
                  AND decision.companyId = e.companyId
                  AND decision.caseId = e.id
                  AND decision.isDeleted = false
                  AND decision.decisionType IN :decisionTypes
            ))
            AND (:executionStatusesEmpty = true OR EXISTS (
                SELECT 1 FROM NonMovingStockExecution execution
                WHERE execution.tenantId = e.tenantId
                  AND execution.companyId = e.companyId
                  AND execution.caseId = e.id
                  AND execution.isDeleted = false
                  AND execution.executionStatus IN :executionStatuses
            ))
            AND (:materialGroupId IS NULL OR EXISTS (
                SELECT 1 FROM NonMovingStockCaseLine line, Material material
                WHERE line.tenantId = e.tenantId
                  AND line.companyId = e.companyId
                  AND line.caseId = e.id
                  AND line.isDeleted = false
                  AND material.tenantId = e.tenantId
                  AND material.companyId = e.companyId
                  AND material.id = line.materialId
                  AND material.isDeleted = false
                  AND material.materialGroupId = :materialGroupId
            ))
            AND (:minBookValue IS NULL OR e.totalBookValue >= :minBookValue)
            AND (:maxBookValue IS NULL OR e.totalBookValue <= :maxBookValue)
            AND (:blockerTypesEmpty = true OR e.blockerType IN :blockerTypes)
            AND (:integrationStatusesEmpty = true OR EXISTS (
                SELECT 1 FROM IntegrationEvent event
                WHERE event.tenantId = e.tenantId
                  AND event.companyId = e.companyId
                  AND event.aggregateType = 'NonMovingStockCase'
                  AND event.aggregateId = e.id
                  AND event.isDeleted = false
                  AND event.status IN :integrationStatuses
            ))
            AND (:exclusionResultsEmpty = true OR EXISTS (
                SELECT 1 FROM NonMovingStockCaseLine line
                WHERE line.tenantId = e.tenantId
                  AND line.companyId = e.companyId
                  AND line.caseId = e.id
                  AND line.isDeleted = false
                  AND line.exclusionResult IN :exclusionResults
            ))
            AND (:activeCaseOnly = false OR (
                e.status NOT IN ('CLOSED', 'CANCELLED')
                AND EXISTS (
                    SELECT 1 FROM NonMovingStockCaseLine line
                    WHERE line.tenantId = e.tenantId
                      AND line.companyId = e.companyId
                      AND line.caseId = e.id
                      AND line.isDeleted = false
                      AND line.activeMembership = true
                )
            ))
        """)
    Page<NonMovingStockCase> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("searchEmpty") boolean searchEmpty,
        @Param("searchPattern") String searchPattern,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("warehouseId") UUID warehouseId,
        @Param("materialIds") List<UUID> materialIds,
        @Param("materialIdsEmpty") boolean materialIdsEmpty,
        @Param("responsibleUserId") UUID responsibleUserId,
        @Param("overdueOnly") boolean overdueOnly,
        @Param("decisionTypes") List<String> decisionTypes,
        @Param("decisionTypesEmpty") boolean decisionTypesEmpty,
        @Param("executionStatuses") List<String> executionStatuses,
        @Param("executionStatusesEmpty") boolean executionStatusesEmpty,
        @Param("materialGroupId") UUID materialGroupId,
        @Param("minBookValue") java.math.BigDecimal minBookValue,
        @Param("maxBookValue") java.math.BigDecimal maxBookValue,
        @Param("blockerTypes") List<String> blockerTypes,
        @Param("blockerTypesEmpty") boolean blockerTypesEmpty,
        @Param("integrationStatuses") List<String> integrationStatuses,
        @Param("integrationStatusesEmpty") boolean integrationStatusesEmpty,
        @Param("exclusionResults") List<String> exclusionResults,
        @Param("exclusionResultsEmpty") boolean exclusionResultsEmpty,
        @Param("activeCaseOnly") boolean activeCaseOnly,
        Pageable pageable
    );
}
