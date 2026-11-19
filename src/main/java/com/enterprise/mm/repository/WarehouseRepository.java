package com.enterprise.mm.repository;

import com.enterprise.mm.entity.Warehouse;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {
    Optional<Warehouse> findAnyById(UUID id);
    Optional<Warehouse> findByIdAndIsDeletedFalse(UUID id);
    Optional<Warehouse> findByTenantIdAndIdAndIsDeletedFalse(String tenantId, UUID id);

    Optional<Warehouse> findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID id);

    Optional<Warehouse> findByTenantIdAndCodeAndIsDeletedFalse(String tenantId, String code);

    List<Warehouse> findByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId);
    
    List<Warehouse> findByTenantIdAndCompanyIdAndIdIn(String tenantId, UUID companyId, java.util.Collection<UUID> ids);

    @Query("""
        SELECT w FROM Warehouse w
        WHERE w.tenantId = :tenantId AND w.companyId = :companyId
          AND w.isDeleted = false AND w.status = 'ACTIVE' AND w.id <> :warehouseId
          AND (:search = '' OR LOWER(w.name) LIKE :search OR LOWER(w.code) LIKE :search)
        """)
    Page<Warehouse> searchManualCounterparts(@Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId, @Param("warehouseId") UUID warehouseId,
        @Param("search") String search, Pageable pageable);

    @Query("""
        SELECT e
        FROM Warehouse e
        WHERE
            e.tenantId = :tenantId
            AND (:companyId IS NULL OR e.companyId = :companyId)
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (:searchPattern IS NULL OR
                 LOWER(COALESCE(e.code, '')) LIKE :searchPattern
                 OR LOWER(COALESCE(e.name, '')) LIKE :searchPattern)
            AND (CAST(:dateFrom AS timestamp) IS NULL OR e.createdAt >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR e.createdAt <= :dateTo)
            AND (:statusEmpty = true OR e.status IN :status)
            AND (:warehouseScope = false OR e.id IN :warehouseIds)
            AND (:id IS NULL OR e.id = :id)
            AND (:codePattern IS NULL OR LOWER(COALESCE(e.code, '')) LIKE :codePattern)
            AND (:namePattern IS NULL OR LOWER(COALESCE(e.name, '')) LIKE :namePattern)
            AND (:descriptionPattern IS NULL OR LOWER(COALESCE(e.description, '')) LIKE :descriptionPattern)
            AND (:deleted IS NULL OR e.isDeleted = :deleted)
            AND (:createdByPattern IS NULL OR LOWER(COALESCE(e.createdBy, '')) LIKE :createdByPattern)
            AND (:updatedByPattern IS NULL OR LOWER(COALESCE(e.updatedBy, '')) LIKE :updatedByPattern)
            AND (:version IS NULL OR e.version = :version)
            AND (:addressPattern IS NULL OR LOWER(COALESCE(e.address, '')) LIKE :addressPattern)
        """)
    Page<Warehouse> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("searchPattern") String searchPattern,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("warehouseScope") boolean warehouseScope,
        @Param("warehouseIds") java.util.Collection<UUID> warehouseIds,
        @Param("id") UUID id,
        @Param("codePattern") String codePattern,
        @Param("namePattern") String namePattern,
        @Param("descriptionPattern") String descriptionPattern,
        @Param("deleted") Boolean deleted,
        @Param("createdByPattern") String createdByPattern,
        @Param("updatedByPattern") String updatedByPattern,
        @Param("version") Long version,
        @Param("addressPattern") String addressPattern,
        Pageable pageable
    );
}
