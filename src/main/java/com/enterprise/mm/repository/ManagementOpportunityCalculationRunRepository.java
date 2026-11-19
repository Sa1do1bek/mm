package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ManagementOpportunityCalculationRun;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ManagementOpportunityCalculationRunRepository extends JpaRepository<ManagementOpportunityCalculationRun, UUID> {
    Optional<ManagementOpportunityCalculationRun> findByIdAndIsDeletedFalse(UUID id);

    Optional<ManagementOpportunityCalculationRun> findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID id
    );

    @Query("""
        select e
        from ManagementOpportunityCalculationRun e
        where e.tenantId = :tenantId
          and e.companyId = :companyId
          and (:includeDeleted = true or e.isDeleted = false)
          and (:status is null or e.status = :status)
          and (:requestedType is null or e.requestedTypes = 'ALL' or lower(e.requestedTypes) like :requestedType)
          and (:targetCurrencyCode is null or e.targetCurrencyCode = :targetCurrencyCode)
          and (cast(:asOfDateFrom as date) is null or e.asOfDate >= :asOfDateFrom)
          and (cast(:asOfDateTo as date) is null or e.asOfDate <= :asOfDateTo)
          and (cast(:startedAtFrom as timestamp) is null or e.startedAt >= :startedAtFrom)
          and (cast(:startedAtTo as timestamp) is null or e.startedAt <= :startedAtTo)
          and (:hasSkipped is null or (:hasSkipped = true and e.skippedCount > 0) or (:hasSkipped = false and e.skippedCount = 0))
          and (:hasFailure is null
               or (:hasFailure = true and e.failureSummary is not null and e.failureSummary <> '')
               or (:hasFailure = false and (e.failureSummary is null or e.failureSummary = '')))
        """)
    Page<ManagementOpportunityCalculationRun> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("status") String status,
        @Param("requestedType") String requestedType,
        @Param("targetCurrencyCode") String targetCurrencyCode,
        @Param("asOfDateFrom") LocalDate asOfDateFrom,
        @Param("asOfDateTo") LocalDate asOfDateTo,
        @Param("startedAtFrom") LocalDateTime startedAtFrom,
        @Param("startedAtTo") LocalDateTime startedAtTo,
        @Param("hasSkipped") Boolean hasSkipped,
        @Param("hasFailure") Boolean hasFailure,
        Pageable pageable
    );
}
