package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.LandedCostAllocationLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LandedCostAllocationLineRepository extends JpaRepository<LandedCostAllocationLine, UUID> {
    Optional<LandedCostAllocationLine> findAnyById(UUID id);
    Optional<LandedCostAllocationLine> findByIdAndIsDeletedFalse(UUID id);
    List<LandedCostAllocationLine> findByTenantIdAndCompanyIdAndAllocationDocumentIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID allocationDocumentId
    );

    @Query("""
        SELECT e
        FROM LandedCostAllocationLine e
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
            AND (:warehouseId IS NULL OR e.warehouseId = :warehouseId)
            AND (:materialIdsEmpty = true OR e.materialId IN :materialIds)
        """)
    Page<LandedCostAllocationLine> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("warehouseId") UUID warehouseId,
        @Param("materialIds") List<UUID> materialIds,
        @Param("materialIdsEmpty") boolean materialIdsEmpty,
        Pageable pageable
    );

    @Query("""
        select count(l) from LandedCostAllocationLine l, LandedCostAllocation a
        where l.isDeleted = false
          and a.isDeleted = false
          and l.tenantId = :tenantId
          and a.tenantId = :tenantId
          and l.companyId = :companyId
          and a.companyId = :companyId
          and l.allocationDocumentId = a.id
          and a.status not in ('POSTED', 'CANCELLED')
          and a.documentDate between :periodStart and :periodEnd
          and (:warehouseIdEmpty = true or l.warehouseId = :warehouseId)
        """)
    long countOpenAllocationLinesInPeriod(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseIdEmpty") boolean warehouseIdEmpty,
        @Param("warehouseId") UUID warehouseId,
        @Param("periodStart") java.time.LocalDate periodStart,
        @Param("periodEnd") java.time.LocalDate periodEnd
    );
}
