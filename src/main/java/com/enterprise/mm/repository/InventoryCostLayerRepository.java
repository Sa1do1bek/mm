package com.enterprise.mm.repository;

import com.enterprise.mm.entity.InventoryCostLayer;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryCostLayerRepository extends JpaRepository<InventoryCostLayer, UUID> {
    interface InventoryValueProjection {
        UUID getWarehouseId();
        UUID getMaterialId();
        UUID getMaterialGroupId();
        UUID getStockBatchId();
        BigDecimal getQuantity();
        BigDecimal getTotalCost();
    }

    Optional<InventoryCostLayer> findByIdAndIsDeletedFalse(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select layer from InventoryCostLayer layer
        where layer.id = :id
          and layer.isDeleted = false
        """)
    Optional<InventoryCostLayer> findByIdAndIsDeletedFalseForUpdate(@Param("id") UUID id);

    List<InventoryCostLayer> findByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId);

    @Query("""
        select l from InventoryCostLayer l
        where l.isDeleted = false
          and l.tenantId = :tenantId
          and l.companyId = :companyId
          and (:warehouseId is null or l.warehouseId = :warehouseId)
          and (:materialId is null or l.materialId = :materialId)
          and (:stockBatchId is null or l.stockBatchId = :stockBatchId)
          and (:dateFrom is null or l.receivedAt >= :dateFrom)
          and (:dateTo is null or l.receivedAt <= :dateTo)
        """)
    Page<InventoryCostLayer> searchFinance(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseId") UUID warehouseId,
        @Param("materialId") UUID materialId,
        @Param("stockBatchId") UUID stockBatchId,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        Pageable pageable
    );

    boolean existsByTenantIdAndCompanyIdAndMaterialIdAndWarehouseIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID materialId,
        UUID warehouseId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select l from InventoryCostLayer l
        where l.isDeleted = false
          and l.tenantId = :tenantId
          and l.companyId = :companyId
          and l.materialId = :materialId
          and l.warehouseId = :warehouseId
          and (:stockBatchId is null or l.stockBatchId = :stockBatchId)
          and l.remainingQuantity > :zero
          and l.status = 'ACTIVE'
        order by l.receivedAt asc, l.createdAt asc, l.id asc
        """)
    List<InventoryCostLayer> findActiveLayersForUpdateAsc(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId,
        @Param("stockBatchId") UUID stockBatchId,
        @Param("zero") BigDecimal zero
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select l from InventoryCostLayer l
        where l.isDeleted = false
          and l.tenantId = :tenantId
          and l.companyId = :companyId
          and l.materialId = :materialId
          and l.warehouseId = :warehouseId
          and (:stockBatchId is null or l.stockBatchId = :stockBatchId)
          and l.remainingQuantity > :zero
          and l.status = 'ACTIVE'
        order by l.receivedAt asc, l.createdAt asc, l.id asc
        """)
    List<InventoryCostLayer> findCapitalizableLayersForUpdate(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId,
        @Param("stockBatchId") UUID stockBatchId,
        @Param("zero") BigDecimal zero
    );

    @Query("""
        select l.warehouseId as warehouseId,
               l.materialId as materialId,
               m.materialGroupId as materialGroupId,
               l.stockBatchId as stockBatchId,
               coalesce(sum(l.remainingQuantity), 0) as quantity,
               coalesce(sum(l.totalCost), 0) as totalCost
        from InventoryCostLayer l, Material m
        where l.isDeleted = false
          and m.isDeleted = false
          and l.tenantId = :tenantId
          and m.tenantId = :tenantId
          and l.companyId = :companyId
          and m.companyId = :companyId
          and l.materialId = m.id
          and l.status = 'ACTIVE'
          and l.remainingQuantity > 0
          and (:warehouseId is null or l.warehouseId = :warehouseId)
          and (:materialId is null or l.materialId = :materialId)
        group by l.warehouseId, l.materialId, m.materialGroupId, l.stockBatchId
        """)
    List<InventoryValueProjection> aggregateActiveInventoryValue(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseId") UUID warehouseId,
        @Param("materialId") UUID materialId
    );
}
