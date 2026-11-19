package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.PutawayRule;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PutawayRuleRepository extends JpaRepository<PutawayRule, UUID> {
    Optional<PutawayRule> findAnyById(UUID id);
    Optional<PutawayRule> findByIdAndIsDeletedFalse(UUID id);
    @Query("""
        select r from PutawayRule r
        where r.isDeleted = false
          and r.tenantId = :tenantId
          and r.companyId = :companyId
          and r.status = 'ACTIVE'
          and (r.warehouseId is null or (:warehouseIdEmpty = false and r.warehouseId = :warehouseId))
        order by r.priority asc, r.createdAt asc
        """)
    List<PutawayRule> findActiveForWarehouse(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseIdEmpty") boolean warehouseIdEmpty,
        @Param("warehouseId") UUID warehouseId
    );

    @Query("""
        SELECT e
        FROM PutawayRule e
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
            AND (:warehouseId IS NULL OR e.warehouseId = :warehouseId OR e.targetStorageLocationId IN (
                SELECT l.id FROM StorageLocation l WHERE l.warehouseId = :warehouseId
                AND l.tenantId = e.tenantId AND l.companyId = e.companyId AND l.isDeleted = false))
            AND (:warehouseIdsEmpty = true OR e.warehouseId IN :warehouseIds OR e.targetStorageLocationId IN (
                SELECT l.id FROM StorageLocation l WHERE l.warehouseId IN :warehouseIds
                AND l.tenantId = e.tenantId AND l.companyId = e.companyId AND l.isDeleted = false))
            AND (:materialIdsEmpty = true OR e.materialId IN :materialIds)
        """)
    Page<PutawayRule> search(
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
        @Param("materialIds") List<UUID> materialIds,
        @Param("materialIdsEmpty") boolean materialIdsEmpty,
        Pageable pageable
    );
}
