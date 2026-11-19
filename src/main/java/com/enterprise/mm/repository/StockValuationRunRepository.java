package com.enterprise.mm.repository;

import com.enterprise.mm.entity.StockValuationRun;
import com.enterprise.mm.entity.StockValuationCostJob;
import com.enterprise.mm.enums.ValuationMethod;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockValuationRunRepository extends JpaRepository<StockValuationRun, UUID> {
    /** SELECT aliases must match these getters; the job snapshot also supplies the response status. */
    interface RunWithJob {
        StockValuationRun getRun();
        StockValuationCostJob getJob();
    }
    Optional<StockValuationRun> findByIdAndIsDeletedFalse(UUID id);

    @Query("""
        select run from StockValuationRun run
        where run.id = :id
          and run.tenantId = :tenantId
          and run.companyId = :companyId
          and run.isDeleted = false
        """)
    Optional<StockValuationRun> findScoped(
        @Param("id") UUID id,
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId
    );

    @Query("""
        SELECT new com.enterprise.mm.payload.RunStatsDto(
            COUNT(r), 
            SUM(CASE WHEN r.status = 'COMPLETED' THEN 1 ELSE 0 END), 
            SUM(CASE WHEN r.status IN ('FAILED', 'COMPLETED_WITH_ERRORS') THEN 1 ELSE 0 END)
        )
        FROM StockValuationRun r 
        WHERE r.tenantId = :tenantId AND r.companyId = :companyId AND r.isDeleted = false
        """)
    com.enterprise.mm.payload.RunStatsDto getStatistics(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId
    );

    @Query("""
        select run as run, job as job from StockValuationRun run
        left join StockValuationCostJob job on job.id = run.jobId
          and job.tenantId = run.tenantId and job.companyId = run.companyId
          and run.status in ('QUEUED', 'RUNNING')
        where run.tenantId = :tenantId
          and run.companyId = :companyId
          and (:includeDeleted = true or run.isDeleted = false)
          and (:searchPattern is null or
               lower(coalesce(run.code, '')) like :searchPattern escape '!'
               or lower(coalesce(run.name, '')) like :searchPattern escape '!')
          and (:statusEmpty = true or coalesce(cast(job.status as string), run.status) in :status)
          and (:warehouseId is null or run.warehouseId = :warehouseId
               or :warehouseId member of run.warehouseIds
               or run.scopeType = com.enterprise.mm.enums.StockValuationRunScope.GLOBAL)
          and (:scopeType is null or run.scopeType = :scopeType)
          and (:valuationMethod is null or run.valuationMethod = :valuationMethod)
          and (cast(:dateFrom as timestamp) is null or run.createdAt >= :dateFrom)
          and (cast(:dateTo as timestamp) is null or run.createdAt <= :dateTo)
        """)
    Page<RunWithJob> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("searchPattern") String searchPattern,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("warehouseId") UUID warehouseId,
        @Param("scopeType") com.enterprise.mm.enums.StockValuationRunScope scopeType,
        @Param("valuationMethod") ValuationMethod valuationMethod,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        Pageable pageable
    );
}
