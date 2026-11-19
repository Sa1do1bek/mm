package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.InventoryPeriodClose;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface InventoryPeriodCloseRepository extends JpaRepository<InventoryPeriodClose, UUID> {
    Optional<InventoryPeriodClose> findAnyById(UUID id);
    Optional<InventoryPeriodClose> findByIdAndIsDeletedFalse(UUID id);
    @Query("""
        select count(p) > 0 from InventoryPeriodClose p
        where p.isDeleted = false
          and p.tenantId = :tenantId
          and p.companyId = :companyId
          and p.status = 'CLOSED'
          and p.periodStart <= :postingDate
          and p.periodEnd >= :postingDate
          and (p.warehouseId is null or (:warehouseIdEmpty = false and p.warehouseId = :warehouseId))
        """)
    boolean existsClosedForPostingDate(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseIdEmpty") boolean warehouseIdEmpty,
        @Param("warehouseId") UUID warehouseId,
        @Param("postingDate") LocalDate postingDate
    );

    @Query("""
        select count(p) > 0 from InventoryPeriodClose p
        where p.isDeleted = false
          and p.tenantId = :tenantId
          and p.companyId = :companyId
          and p.status = 'CLOSED'
          and p.periodStart <= :periodEnd
          and p.periodEnd >= :periodStart
          and ((:warehouseIdEmpty = true and p.warehouseId is null) or (:warehouseIdEmpty = false and p.warehouseId = :warehouseId))
          and (:excludeIdEmpty = true or p.id <> :excludeId)
        """)
    boolean existsClosedOverlap(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseIdEmpty") boolean warehouseIdEmpty,
        @Param("warehouseId") UUID warehouseId,
        @Param("periodStart") LocalDate periodStart,
        @Param("periodEnd") LocalDate periodEnd,
        @Param("excludeIdEmpty") boolean excludeIdEmpty,
        @Param("excludeId") UUID excludeId
    );

    @Query("""
        select p from InventoryPeriodClose p
        where p.isDeleted = false
          and p.tenantId = :tenantId
          and p.companyId = :companyId
          and p.status <> 'CLOSED'
          and p.periodStart = :periodStart
          and p.periodEnd = :periodEnd
          and ((:warehouseIdEmpty = true and p.warehouseId is null) or (:warehouseIdEmpty = false and p.warehouseId = :warehouseId))
        order by p.createdAt desc
        """)
    List<InventoryPeriodClose> findOpenByPeriod(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseIdEmpty") boolean warehouseIdEmpty,
        @Param("warehouseId") UUID warehouseId,
        @Param("periodStart") LocalDate periodStart,
        @Param("periodEnd") LocalDate periodEnd
    );

    @Query("""
        SELECT e
        FROM InventoryPeriodClose e
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
        """)
    Page<InventoryPeriodClose> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("warehouseId") UUID warehouseId,
        Pageable pageable
    );
}
