package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.SupplierEvaluation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierEvaluationRepository extends JpaRepository<SupplierEvaluation, UUID> {
    Optional<SupplierEvaluation> findAnyById(UUID id);
    Optional<SupplierEvaluation> findByIdAndIsDeletedFalse(UUID id);
    List<SupplierEvaluation> findByTenantIdAndSupplier_IdAndIsDeletedFalse(String tenantId, UUID supplierId);
    Optional<SupplierEvaluation> findByTenantIdAndCompanyIdAndStockMovementIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID stockMovementId);

    @Query("""
        SELECT e
        FROM SupplierEvaluation e
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
            AND (:supplierId IS NULL OR e.supplierId = :supplierId)
            AND (:purchaseOrderId IS NULL OR e.purchaseOrderId = :purchaseOrderId)
            AND (:materialIdsEmpty = true OR e.materialId IN :materialIds)
            AND (:totalScoreMin IS NULL OR e.totalScore >= :totalScoreMin)
            AND (:totalScoreMax IS NULL OR e.totalScore <= :totalScoreMax)
            AND (CAST(:evaluatedFrom AS timestamp) IS NULL OR e.evaluatedAt >= :evaluatedFrom)
            AND (CAST(:evaluatedTo AS timestamp) IS NULL OR e.evaluatedAt <= :evaluatedTo)
        """)
    Page<SupplierEvaluation> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("searchPattern") String searchPattern,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("supplierId") UUID supplierId,
        @Param("purchaseOrderId") UUID purchaseOrderId,
        @Param("materialIds") List<UUID> materialIds,
        @Param("materialIdsEmpty") boolean materialIdsEmpty,
        @Param("totalScoreMin") java.math.BigDecimal totalScoreMin,
        @Param("totalScoreMax") java.math.BigDecimal totalScoreMax,
        @Param("evaluatedFrom") LocalDateTime evaluatedFrom,
        @Param("evaluatedTo") LocalDateTime evaluatedTo,
        Pageable pageable
    );
}
