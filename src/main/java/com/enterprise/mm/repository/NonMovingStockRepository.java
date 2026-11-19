package com.enterprise.mm.repository;

import com.enterprise.mm.entity.NonMovingStock;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NonMovingStockRepository extends JpaRepository<NonMovingStock, UUID> {
    Optional<NonMovingStock> findAnyById(UUID id);
    Optional<NonMovingStock> findByIdAndIsDeletedFalse(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from NonMovingStock e where e.id = :id and e.isDeleted = false")
    Optional<NonMovingStock> findByIdAndIsDeletedFalseForUpdate(@Param("id") UUID id);

    @Query("""
        select case when count(stock) > 0 then true else false end
        from NonMovingStock stock
        where stock.isDeleted = false
          and stock.tenantId = :tenantId
          and stock.companyId = :companyId
          and stock.materialId = :materialId
          and (:warehouseId is null or stock.warehouseId = :warehouseId)
          and stock.status in ('DETECTED', 'CASE_CREATED', 'UNDER_REVIEW', 'COMMISSION_REVIEW',
              'DECISION_APPROVED', 'IN_EXECUTION')
        """)
    boolean existsMaterial360Active(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId
    );

    @Query("""
        select count(stock)
        from NonMovingStock stock
        where stock.isDeleted = false
          and stock.tenantId = :tenantId
          and stock.companyId = :companyId
          and stock.materialId = :materialId
          and (:warehouseId is null or stock.warehouseId = :warehouseId)
          and stock.status in ('DETECTED', 'CASE_CREATED', 'UNDER_REVIEW', 'COMMISSION_REVIEW',
              'DECISION_APPROVED', 'IN_EXECUTION')
        """)
    long countMaterial360Active(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId
    );
    Optional<NonMovingStock> findFirstByTenantIdAndCompanyIdAndMaterialIdAndWarehouseIdAndStatusInAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId,
        UUID companyId,
        UUID materialId,
        UUID warehouseId,
        List<String> statuses
    );

    List<NonMovingStock> findByTenantIdAndIsDeletedFalse(String tenantId);

    List<NonMovingStock> findByTenantIdAndIsDeletedFalse(String tenantId, Pageable pageable);

    List<NonMovingStock> findByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId);

    List<NonMovingStock> findByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId, Pageable pageable);

    long countByTenantIdAndStatusNotAndIsDeletedFalse(String tenantId, String status);

    long countByTenantIdAndCompanyIdAndStatusNotAndIsDeletedFalse(String tenantId, UUID companyId, String status);

    @Query("""
        SELECT e
        FROM NonMovingStock e
        WHERE
            e.tenantId = :tenantId
            AND (:companyId IS NULL OR e.companyId = :companyId)
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (:searchEmpty = true OR
                 LOWER(COALESCE(e.code, '')) LIKE :searchPattern
                 OR LOWER(COALESCE(e.name, '')) LIKE :searchPattern)
            AND (CAST(:dateFrom AS timestamp) IS NULL OR COALESCE(e.detectedAt, e.createdAt) >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR COALESCE(e.detectedAt, e.createdAt) <= :dateTo)
            AND (:statusEmpty = true OR e.status IN :status)
            AND (:warehouseId IS NULL OR e.warehouseId = :warehouseId)
            AND (:warehouseIdsEmpty = true OR e.warehouseId IN :warehouseIds)
            AND (:materialIdsEmpty = true OR e.materialId IN :materialIds)
            AND (:materialGroupId IS NULL OR EXISTS (
                SELECT 1 FROM Material material
                WHERE material.tenantId = e.tenantId
                  AND material.companyId = e.companyId
                  AND material.id = e.materialId
                  AND material.isDeleted = false
                  AND material.materialGroupId = :materialGroupId
            ))
            AND (:activeCaseOnly = false OR EXISTS (
                SELECT 1 FROM NonMovingStockCaseLine line, NonMovingStockCase stockCase
                WHERE line.tenantId = e.tenantId
                  AND stockCase.tenantId = e.tenantId
                  AND line.companyId = e.companyId
                  AND stockCase.companyId = e.companyId
                  AND line.nonMovingStockId = e.id
                  AND line.caseId = stockCase.id
                  AND line.isDeleted = false
                  AND stockCase.isDeleted = false
                  AND line.activeMembership = true
                  AND stockCase.status NOT IN ('CLOSED', 'CANCELLED')
            ))
        """)
    Page<NonMovingStock> search(
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
        @Param("warehouseIds") java.util.List<UUID> warehouseIds,
        @Param("warehouseIdsEmpty") boolean warehouseIdsEmpty,
        @Param("materialIds") List<UUID> materialIds,
        @Param("materialIdsEmpty") boolean materialIdsEmpty,
        @Param("materialGroupId") UUID materialGroupId,
        @Param("activeCaseOnly") boolean activeCaseOnly,
        Pageable pageable
    );
}
