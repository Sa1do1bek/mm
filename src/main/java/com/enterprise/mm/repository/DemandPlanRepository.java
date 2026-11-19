package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.DemandPlan;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DemandPlanRepository extends JpaRepository<DemandPlan, UUID>, JpaSpecificationExecutor<DemandPlan> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from DemandPlan e where e.id = :id and e.tenantId = :tenant and e.companyId = :company and e.isDeleted = false")
    Optional<DemandPlan> lockSource(@Param("id") UUID id, @Param("tenant") String tenant, @Param("company") UUID company);
    Optional<DemandPlan> findAnyById(UUID id);
    Optional<DemandPlan> findByIdAndIsDeletedFalse(UUID id);
    List<DemandPlan> findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        Collection<UUID> ids
    );
    List<DemandPlan> findByTenantIdAndPlanningCycle_IdAndIsDeletedFalse(String tenantId, UUID planningCycleId);

    @Query("""
        SELECT e
        FROM DemandPlan e
        WHERE
            e.tenantId = :tenantId
            AND (:companyId IS NULL OR e.companyId = :companyId)
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (:search IS NULL OR
                 LOWER(COALESCE(e.code, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(COALESCE(e.name, '')) LIKE LOWER(CONCAT('%', :search, '%')))
            AND (CAST(:dateFrom AS timestamp) IS NULL OR e.createdAt >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR e.createdAt <= :dateTo)
            AND (:statusEmpty = true OR e.status IN :status)
        """)
    Page<DemandPlan> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        Pageable pageable
    );
}
