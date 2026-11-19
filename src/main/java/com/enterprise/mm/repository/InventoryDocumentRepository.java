package com.enterprise.mm.repository;

import com.enterprise.mm.entity.InventoryDocument;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryDocumentRepository extends JpaRepository<InventoryDocument, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from InventoryDocument d where d.id=:id and d.tenantId=:tenant and d.companyId=:company and d.isDeleted=false")
    Optional<InventoryDocument> findScopedForUpdate(@Param("id") UUID id,@Param("tenant") String tenant,@Param("company") UUID company);
    @Query("select d from InventoryDocument d where d.tenantId=:tenant and d.companyId=:company and d.isDeleted=false and d.freezeOperations=true and d.snapshotAt is not null and d.status in :statuses")
    List<InventoryDocument> findActiveFreezes(@Param("tenant") String tenant,@Param("company") UUID company,@Param("statuses") List<String> statuses);
    Optional<InventoryDocument> findAnyById(UUID id);
    Optional<InventoryDocument> findByIdAndIsDeletedFalse(UUID id);
    Optional<InventoryDocument> findByTenantIdAndIdAndIsDeletedFalse(String tenantId, UUID id);

    Optional<InventoryDocument> findByTenantIdAndCodeAndIsDeletedFalse(String tenantId, String code);

    @Query("""
        select count(e) > 0 from InventoryDocument e
        where e.isDeleted = false
          and e.tenantId = :tenantId
          and e.companyId = :companyId
          and e.freezeOperations = true
          and (e.globalWarehouse = true or e.warehouseId = :warehouseId or exists (
              select 1 from InventoryDocument scoped join scoped.resolvedWarehouseIds resolved
              where scoped.id = e.id and resolved = :warehouseId))
          and e.status in :statuses
        """)
    boolean existsActiveFreezeForWarehouse(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseId") UUID warehouseId,
        @Param("statuses") List<String> statuses
    );

    @Query("""
        select count(e) > 0 from InventoryDocument e
        where e.isDeleted = false
          and e.tenantId = :tenantId
          and e.companyId = :companyId
          and e.freezeOperations = true
          and e.status in :statuses
          and e.id <> :excludedId
          and (e.globalWarehouse = true or e.warehouseId = :warehouseId or exists (
              select 1 from InventoryDocument scoped join scoped.resolvedWarehouseIds resolved
              where scoped.id = e.id and resolved = :warehouseId))
        """)
    boolean existsActiveFreezeForWarehouseExcluding(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseId") UUID warehouseId,
        @Param("statuses") List<String> statuses,
        @Param("excludedId") UUID excludedId
    );

    @Query("""
        select count(e) > 0 from InventoryDocument e
        where e.isDeleted = false
          and e.tenantId = :tenantId
          and e.companyId = :companyId
          and e.freezeOperations = true
          and e.status in :statuses
          and e.id <> :excludedId
        """)
    boolean existsActiveFreezeForCompanyExcluding(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("statuses") List<String> statuses,
        @Param("excludedId") UUID excludedId
    );

    @Query("""
        SELECT e
        FROM InventoryDocument e
        WHERE
            e.tenantId = :tenantId
            AND (:companyId IS NULL OR e.companyId = :companyId)
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (:search IS NULL OR
                 LOWER(COALESCE(e.code, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(CONCAT('INV-', lpad(cast(e.inventorySequence as string), greatest(6, length(cast(e.inventorySequence as string))), '0'))) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(COALESCE(e.name, '')) LIKE LOWER(CONCAT('%', :search, '%')))
            AND (CAST(:dateFrom AS timestamp) IS NULL OR e.createdAt >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR e.createdAt <= :dateTo)
            AND (:statusEmpty = true OR e.status IN :status)
            AND (:warehouseId IS NULL OR e.warehouseId = :warehouseId OR EXISTS (
                SELECT 1 FROM InventoryDocument scopedOne JOIN scopedOne.resolvedWarehouseIds warehouseOne
                WHERE scopedOne.id = e.id AND warehouseOne = :warehouseId))
            AND (:warehouseIdsEmpty = true OR e.warehouseId IN :warehouseIds OR EXISTS (
                SELECT 1 FROM InventoryDocument scopedMany JOIN scopedMany.resolvedWarehouseIds warehouseMany
                WHERE scopedMany.id = e.id AND warehouseMany IN :warehouseIds))
            AND (:readAll = true OR e.scopeMode IS NULL OR :warehouseIdsEmpty = true OR NOT EXISTS (
                SELECT 1 FROM InventoryDocument accessScope JOIN accessScope.resolvedWarehouseIds requiredWarehouse
                WHERE accessScope.id = e.id AND requiredWarehouse NOT IN :warehouseIds))
            AND (:readAll = true OR (:warehouseReader = true AND e.globalWarehouse = false) OR EXISTS (SELECT cm.id FROM CommissionMember cm
                WHERE cm.tenantId=e.tenantId AND cm.companyId=e.companyId AND cm.commissionId=e.commissionId
                AND cm.userId=:readerId AND cm.active=true AND cm.isDeleted=false))
        """)
    Page<InventoryDocument> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("warehouseId") UUID warehouseId,
        @Param("warehouseIds") java.util.List<UUID> warehouseIds,
        @Param("warehouseIdsEmpty") boolean warehouseIdsEmpty,
        @Param("readAll") boolean readAll,
        @Param("warehouseReader") boolean warehouseReader,
        @Param("readerId") UUID readerId,
        Pageable pageable
    );
}
