package com.enterprise.mm.repository;

import com.enterprise.mm.entity.StockCostMovement;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockCostMovementRepository extends JpaRepository<StockCostMovement, UUID> {
    interface CostMovementAmountProjection {
        UUID getWarehouseId();
        UUID getMaterialId();
        BigDecimal getTotalAmount();
    }

    List<StockCostMovement> findByTenantIdAndCompanyIdAndStockMovementIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID stockMovementId);

    List<StockCostMovement> findByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId);

    Optional<StockCostMovement> findByIdAndIsDeletedFalse(UUID id);

    List<StockCostMovement> findByTenantIdAndCompanyIdAndSourceDocumentTypeAndSourceDocumentIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        String sourceDocumentType,
        UUID sourceDocumentId
    );

    List<StockCostMovement> findByTenantIdAndCompanyIdAndTransferTransactionIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID transferTransactionId
    );

    @Query("""
        select m from StockCostMovement m
        where m.isDeleted = false
          and m.tenantId = :tenantId
          and m.companyId = :companyId
          and (:warehouseId is null or m.warehouseId = :warehouseId)
          and (:materialId is null or m.materialId = :materialId)
          and (:stockMovementId is null or m.stockMovementId = :stockMovementId)
          and (:costLayerId is null or m.costLayerId = :costLayerId)
          and (:movementType is null or m.movementType = :movementType)
          and (:sourceDocumentType is null or m.sourceDocumentType = :sourceDocumentType)
          and (:sourceDocumentId is null or m.sourceDocumentId = :sourceDocumentId)
          and (:dateFrom is null or m.createdAt >= :dateFrom)
          and (:dateTo is null or m.createdAt <= :dateTo)
        """)
    Page<StockCostMovement> searchFinance(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseId") UUID warehouseId,
        @Param("materialId") UUID materialId,
        @Param("stockMovementId") UUID stockMovementId,
        @Param("costLayerId") UUID costLayerId,
        @Param("movementType") String movementType,
        @Param("sourceDocumentType") String sourceDocumentType,
        @Param("sourceDocumentId") UUID sourceDocumentId,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        Pageable pageable
    );

    @Query("""
        select m.warehouseId as warehouseId,
               m.materialId as materialId,
               coalesce(sum(m.totalCost), 0) as totalAmount
        from StockCostMovement m
        where m.isDeleted = false
          and m.tenantId = :tenantId
          and m.companyId = :companyId
          and m.movementType = :movementType
          and (:warehouseId is null or m.warehouseId = :warehouseId)
          and (:materialId is null or m.materialId = :materialId)
        group by m.warehouseId, m.materialId
        """)
    List<CostMovementAmountProjection> aggregateAmountByType(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("movementType") String movementType,
        @Param("warehouseId") UUID warehouseId,
        @Param("materialId") UUID materialId
    );
}
