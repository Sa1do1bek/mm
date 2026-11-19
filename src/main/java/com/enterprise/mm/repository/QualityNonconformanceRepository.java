package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.QualityNonconformance;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QualityNonconformanceRepository extends JpaRepository<QualityNonconformance, UUID> {
    Optional<QualityNonconformance> findAnyById(UUID id);
    Optional<QualityNonconformance> findByIdAndIsDeletedFalse(UUID id);
    List<QualityNonconformance> findByTenantIdAndCompanyIdAndQualityInspection_IdAndIsDeletedFalse(String tenantId, UUID companyId, UUID qualityInspectionId);

    @Query("""
        SELECT e
        FROM QualityNonconformance e
        WHERE
            e.tenantId = :tenantId
            AND (:companyId IS NULL OR e.companyId = :companyId)
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (:warehouseId IS NULL OR EXISTS (
                SELECT i.id FROM QualityInspection i WHERE i.id = e.qualityInspectionId
                AND i.warehouseId = :warehouseId AND i.tenantId = e.tenantId AND i.companyId = e.companyId))
            AND (:search IS NULL OR
                 LOWER(COALESCE(e.code, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(COALESCE(e.name, '')) LIKE LOWER(CONCAT('%', :search, '%')))
            AND (CAST(:dateFrom AS timestamp) IS NULL OR e.createdAt >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR e.createdAt <= :dateTo)
            AND (:statusEmpty = true OR e.status IN :status)
            AND (:supplierId IS NULL OR e.supplierId = :supplierId)
            AND (:purchaseOrderId IS NULL OR e.purchaseOrderId = :purchaseOrderId)
            AND (:materialIdsEmpty = true OR e.materialId IN :materialIds)
        """)
    Page<QualityNonconformance> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("supplierId") UUID supplierId,
        @Param("purchaseOrderId") UUID purchaseOrderId,
        @Param("materialIds") List<UUID> materialIds,
        @Param("materialIdsEmpty") boolean materialIdsEmpty,
        @Param("warehouseId") UUID warehouseId,
        Pageable pageable
    );
}
