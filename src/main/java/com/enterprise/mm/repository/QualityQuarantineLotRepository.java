package com.enterprise.mm.repository;

import com.enterprise.mm.entity.QualityQuarantineLot;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface QualityQuarantineLotRepository extends JpaRepository<QualityQuarantineLot, UUID> {
    Optional<QualityQuarantineLot> findAnyById(UUID id);
    Optional<QualityQuarantineLot> findByIdAndIsDeletedFalse(UUID id);
    Optional<QualityQuarantineLot> findByTenantIdAndCompanyIdAndQuarantineNumberAndIsDeletedFalse(String tenantId, UUID companyId, String quarantineNumber);

    List<QualityQuarantineLot> findByTenantIdAndCompanyIdAndQualityInspection_IdAndIsDeletedFalse(String tenantId, UUID companyId, UUID qualityInspectionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select q from QualityQuarantineLot q
        where q.isDeleted = false
          and q.tenantId = :tenantId
          and q.companyId = :companyId
          and q.qualityInspection.id = :qualityInspectionId
        order by q.createdAt desc
        """)
    List<QualityQuarantineLot> findByInspectionForUpdate(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("qualityInspectionId") UUID qualityInspectionId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select q from QualityQuarantineLot q
        where q.isDeleted = false
          and q.tenantId = :tenantId
          and q.companyId = :companyId
          and q.id = :id
        """)
    Optional<QualityQuarantineLot> findCompanyLotForUpdate(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("id") UUID id
    );

    @Query("""
        SELECT e
        FROM QualityQuarantineLot e
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
            AND (:supplierId IS NULL OR e.supplierId = :supplierId)
            AND (:purchaseOrderId IS NULL OR e.purchaseOrderId = :purchaseOrderId)
            AND (:materialIdsEmpty = true OR e.materialId IN :materialIds)
        """)
    Page<QualityQuarantineLot> search(
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
        @Param("supplierId") UUID supplierId,
        @Param("purchaseOrderId") UUID purchaseOrderId,
        @Param("materialIds") List<UUID> materialIds,
        @Param("materialIdsEmpty") boolean materialIdsEmpty,
        Pageable pageable
    );
}
