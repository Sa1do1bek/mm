package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.DemandPlanningCycle;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DemandPlanningCycleRepository extends JpaRepository<DemandPlanningCycle, UUID>, JpaSpecificationExecutor<DemandPlanningCycle> {
    Optional<DemandPlanningCycle> findAnyById(UUID id);
    Optional<DemandPlanningCycle> findByIdAndIsDeletedFalse(UUID id);
    @Query("""
        SELECT e
        FROM DemandPlanningCycle e
        WHERE
            e.tenantId = :tenantId
            AND (:companyId IS NULL OR e.companyId = :companyId)
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (:search IS NULL OR
                 LOWER(COALESCE(e.code, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(COALESCE(e.name, '')) LIKE LOWER(CONCAT('%', :search, '%')))
            AND (:statusEmpty = true OR e.status IN :status)
            AND (CAST(:dateFrom AS timestamp) IS NULL OR e.createdAt >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR e.createdAt <= :dateTo)
            AND (:periodType IS NULL OR UPPER(COALESCE(e.periodType, '')) = :periodType)
        """)
    Page<DemandPlanningCycle> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("periodType") String periodType,
        Pageable pageable
    );

}
