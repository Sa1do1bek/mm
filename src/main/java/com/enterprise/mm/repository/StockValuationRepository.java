package com.enterprise.mm.repository;

import com.enterprise.mm.entity.StockValuation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface StockValuationRepository extends JpaRepository<StockValuation, UUID> {
    Optional<StockValuation> findAnyById(UUID id);
    Optional<StockValuation> findByIdAndIsDeletedFalse(UUID id);
    Optional<StockValuation> findByTenantIdAndMaterialIdAndWarehouseIdAndValuationMethodAndIsDeletedFalse(String tenantId, UUID materialId, UUID warehouseId, com.enterprise.mm.enums.ValuationMethod valuationMethod);

    @Query("""
        select count(v) from StockValuation v
        where v.isDeleted = false
          and v.tenantId = :tenantId
          and v.companyId = :companyId
          and (:warehouseIdEmpty = true or v.warehouseId = :warehouseId)
        """)
    long countActive(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseIdEmpty") boolean warehouseIdEmpty,
        @Param("warehouseId") UUID warehouseId
    );



    @Query("""
        SELECT e
        FROM StockValuation e
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
            AND (:warehouseIdsEmpty = true OR e.warehouseId IN :warehouseIds)
            AND (:materialIdsEmpty = true OR e.materialId IN :materialIds)
        """)
    Page<StockValuation> search(
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
