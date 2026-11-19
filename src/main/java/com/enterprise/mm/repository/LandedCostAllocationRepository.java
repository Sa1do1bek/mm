package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.LandedCostAllocation;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface LandedCostAllocationRepository extends JpaRepository<LandedCostAllocation, UUID> {
    Optional<LandedCostAllocation> findAnyById(UUID id);
    Optional<LandedCostAllocation> findByIdAndIsDeletedFalse(UUID id);
    List<LandedCostAllocation> findByTenantIdAndCompanyIdAndStatusAndIsDeletedFalse(String tenantId, UUID companyId, String status);

    @Query("""
        select coalesce(sum(a.baseTotalAmount), 0) from LandedCostAllocation a
        where a.isDeleted = false
          and a.tenantId = :tenantId
          and a.companyId = :companyId
          and a.status = 'POSTED'
          and a.documentDate between :periodStart and :periodEnd
        """)
    java.math.BigDecimal sumPostedBaseAmount(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("periodStart") LocalDate periodStart,
        @Param("periodEnd") LocalDate periodEnd
    );

    @Query("""
        SELECT e
        FROM LandedCostAllocation e
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
    Page<LandedCostAllocation> search(
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
