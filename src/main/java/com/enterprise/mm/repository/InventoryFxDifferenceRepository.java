package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.InventoryFxDifference;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface InventoryFxDifferenceRepository extends JpaRepository<InventoryFxDifference, UUID> {
    Optional<InventoryFxDifference> findAnyById(UUID id);
    Optional<InventoryFxDifference> findByIdAndIsDeletedFalse(UUID id);
    @Query("""
        select coalesce(sum(f.differenceAmount), 0) from InventoryFxDifference f
        where f.isDeleted = false
          and f.tenantId = :tenantId
          and f.companyId = :companyId
          and f.status = 'POSTED'
          and f.periodStart >= :periodStart
          and f.periodEnd <= :periodEnd
          and (:warehouseIdEmpty = true or f.warehouseId = :warehouseId)
        """)
    BigDecimal sumPostedDifference(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseIdEmpty") boolean warehouseIdEmpty,
        @Param("warehouseId") UUID warehouseId,
        @Param("periodStart") LocalDate periodStart,
        @Param("periodEnd") LocalDate periodEnd
    );

    @Query("""
        SELECT e
        FROM InventoryFxDifference e
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
            AND (:supplierId IS NULL OR e.supplierId = :supplierId)
            AND (:materialIdsEmpty = true OR e.materialId IN :materialIds)
            AND (:referenceId IS NULL OR e.referenceId = :referenceId)
        """)
    Page<InventoryFxDifference> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("warehouseId") UUID warehouseId,
        @Param("supplierId") UUID supplierId,
        @Param("materialIds") List<UUID> materialIds,
        @Param("materialIdsEmpty") boolean materialIdsEmpty,
        @Param("referenceId") UUID referenceId,
        Pageable pageable
    );
}
