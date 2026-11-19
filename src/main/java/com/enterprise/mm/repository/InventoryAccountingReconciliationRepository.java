package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.InventoryAccountingReconciliation;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface InventoryAccountingReconciliationRepository extends JpaRepository<InventoryAccountingReconciliation, UUID> {
    Optional<InventoryAccountingReconciliation> findAnyById(UUID id);
    Optional<InventoryAccountingReconciliation> findByIdAndIsDeletedFalse(UUID id);
    @Query("""
        select r from InventoryAccountingReconciliation r
        where r.isDeleted = false
          and r.tenantId = :tenantId
          and r.companyId = :companyId
          and r.periodStart = :periodStart
          and r.periodEnd = :periodEnd
          and r.accountingSystem = :accountingSystem
          and ((:warehouseIdEmpty = true and r.warehouseId is null) or (:warehouseIdEmpty = false and r.warehouseId = :warehouseId))
        order by case when r.status = 'POSTED' then 0 else 1 end, r.createdAt desc
        """)
    List<InventoryAccountingReconciliation> findByPeriodAndSystem(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseIdEmpty") boolean warehouseIdEmpty,
        @Param("warehouseId") UUID warehouseId,
        @Param("periodStart") LocalDate periodStart,
        @Param("periodEnd") LocalDate periodEnd,
        @Param("accountingSystem") String accountingSystem
    );

    @Query("""
        SELECT e
        FROM InventoryAccountingReconciliation e
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
    Page<InventoryAccountingReconciliation> search(
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
