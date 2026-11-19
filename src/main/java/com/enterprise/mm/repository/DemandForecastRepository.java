package com.enterprise.mm.repository;

import com.enterprise.mm.entity.DemandForecast;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DemandForecastRepository extends JpaRepository<DemandForecast, UUID>, JpaSpecificationExecutor<DemandForecast> {
    Optional<DemandForecast> findAnyById(UUID id);
    Optional<DemandForecast> findByIdAndIsDeletedFalse(UUID id);
    Optional<DemandForecast> findFirstByTenantIdAndCompanyIdAndMaterialIdAndWarehouseIdAndIsDeletedFalseOrderByGeneratedAtDesc(
        String tenantId,
        UUID companyId,
        UUID materialId,
        UUID warehouseId
    );

    @Query("""
        SELECT e
        FROM DemandForecast e
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
    Page<DemandForecast> search(
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
}
