package com.enterprise.mm.repository;

import com.enterprise.mm.entity.StockValuationCostJob;
import com.enterprise.mm.enums.StockValuationCostJobStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface StockValuationCostJobRepository extends JpaRepository<StockValuationCostJob, UUID> {
    @Query("""
        select job from StockValuationCostJob job
        where job.id = :id and job.tenantId = :tenantId and job.companyId = :companyId
        """)
    Optional<StockValuationCostJob> findScoped(
        @Param("id") UUID id,
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId
    );

    @Query("""
        select job from StockValuationCostJob job
        where job.tenantId = :tenantId
          and job.companyId = :companyId
          and job.status in :statuses
          and ((:warehouseId is null and job.warehouseId is null)
            or (:warehouseId is not null and job.warehouseId = :warehouseId))
        order by job.createdAt desc
        """)
    Optional<StockValuationCostJob> findActive(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseId") UUID warehouseId,
        @Param("statuses") Collection<StockValuationCostJobStatus> statuses
    );

    default Optional<StockValuationCostJob> findActive(String tenantId, UUID companyId, UUID warehouseId) {
        return findActive(tenantId, companyId, warehouseId, List.of(
            StockValuationCostJobStatus.QUEUED,
            StockValuationCostJobStatus.RUNNING
        ));
    }

    @Query("""
        select job from StockValuationCostJob job
        where job.tenantId = :tenantId and job.companyId = :companyId
        order by job.createdAt desc
        """)
    Page<StockValuationCostJob> findHistory(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from StockValuationCostJob job where job.id = :id")
    Optional<StockValuationCostJob> findForUpdate(@Param("id") UUID id);

    @Query("""
        select job from StockValuationCostJob job
        where job.status in :statuses and job.createdAt < :startedAt
        """)
    List<StockValuationCostJob> findAbandoned(
        @Param("statuses") Collection<StockValuationCostJobStatus> statuses,
        @Param("startedAt") LocalDateTime startedAt
    );

    @EntityGraph(attributePaths = {"warehouseIds"})
    Optional<StockValuationCostJob> findWithWarehouseIdsById(UUID id);
}
