package com.enterprise.mm.repository;

import com.enterprise.mm.entity.StockBatch;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockBatchRepository extends JpaRepository<StockBatch, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("select b from StockBatch b where b.id = :id and b.isDeleted = false")
    Optional<StockBatch> findForTransferUpdate(@Param("id") UUID id);

    Optional<StockBatch> findAnyById(UUID id);
    Optional<StockBatch> findByIdAndIsDeletedFalse(UUID id);

    @Query("""
        select b from StockBatch b where b.tenantId = :tenantId and b.companyId = :companyId
          and b.isDeleted = false and b.materialId = :materialId and b.warehouseId = :warehouseId
          and (lower(coalesce(b.batchNumber, '')) like lower(concat('%', :search, '%'))
            or lower(coalesce(b.serialNumber, '')) like lower(concat('%', :search, '%'))
            or lower(coalesce(b.name, '')) like lower(concat('%', :search, '%')))
        """)
    Page<StockBatch> findInventoryIdentityCandidates(@Param("tenantId") String tenantId, @Param("companyId") UUID companyId,
        @Param("materialId") UUID materialId, @Param("warehouseId") UUID warehouseId, @Param("search") String search, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select batch from StockBatch batch
        where batch.id = :id
          and batch.isDeleted = false
        """)
    Optional<StockBatch> findByIdAndIsDeletedFalseForUpdate(@Param("id") UUID id);

    List<StockBatch> findByTenantIdAndCompanyIdAndWarehouseIdAndIsDeletedFalseOrderByReceivedDateAscCreatedAtAsc(String tenantId, UUID companyId, UUID warehouseId);
    List<StockBatch> findByTenantIdAndCompanyIdAndQuantityGreaterThanAndIsDeletedFalse(String tenantId, UUID companyId, java.math.BigDecimal quantity);
    List<StockBatch> findByTenantIdAndMaterial_IdAndWarehouse_IdAndQuantityGreaterThanAndIsDeletedFalseOrderByReceivedDateAscCreatedAtAsc(String tenantId, UUID materialId, UUID warehouseId, java.math.BigDecimal quantity);
    List<StockBatch> findByTenantIdAndMaterial_IdAndWarehouse_IdAndQuantityGreaterThanAndIsDeletedFalseOrderByReceivedDateDescCreatedAtDesc(String tenantId, UUID materialId, UUID warehouseId, java.math.BigDecimal quantity);

    @Query("""
        select b from StockBatch b
        where b.isDeleted = false
          and b.tenantId = :tenantId
          and b.materialId = :materialId
          and b.warehouseId = :warehouseId
          and exists (
              select 1 from StockBalance sb
              where sb.isDeleted = false
                and sb.tenantId = b.tenantId
                and sb.materialId = b.materialId
                and sb.warehouseId = b.warehouseId
                and sb.stockBatchId = b.id
                and sb.availableQuantity > 0
          )
        order by b.receivedDate asc, b.createdAt asc
        """)
    List<StockBatch> findAvailableBatchesForFifo(
        @Param("tenantId") String tenantId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId
    );

    @Query("""
        select b from StockBatch b
        where b.isDeleted = false
          and b.tenantId = :tenantId
          and b.materialId = :materialId
          and b.warehouseId = :warehouseId
          and exists (
              select 1 from StockBalance sb
              where sb.isDeleted = false
                and sb.tenantId = b.tenantId
                and sb.materialId = b.materialId
                and sb.warehouseId = b.warehouseId
                and sb.stockBatchId = b.id
                and sb.availableQuantity > 0
          )
        order by b.receivedDate desc, b.createdAt desc
        """)
    List<StockBatch> findAvailableBatchesForLifo(
        @Param("tenantId") String tenantId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId
    );

    Optional<StockBatch> findFirstByTenantIdAndBarcodeAndIsDeletedFalse(String tenantId, String barcode);

    Optional<StockBatch> findFirstByTenantIdAndQrCodeAndIsDeletedFalse(String tenantId, String qrCode);

    Optional<StockBatch> findFirstByTenantIdAndRfidTagAndIsDeletedFalse(String tenantId, String rfidTag);

    @Query("""
        SELECT e
        FROM StockBatch e
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
    Page<StockBatch> search(
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

    @Query("""
        select b from StockBatch b
        where b.isDeleted = false
          and b.tenantId = :tenantId
          and b.materialId = :materialId
          and (:warehouseIdsEmpty = true or b.warehouseId in :warehouseIds)
          and b.quantity > :quantity
        order by b.receivedDate asc, b.createdAt asc
        """)
    List<StockBatch> findBatchesForValuationAsc(
        @Param("tenantId") String tenantId,
        @Param("materialId") UUID materialId,
        @Param("warehouseIdsEmpty") boolean warehouseIdsEmpty,
        @Param("warehouseIds") List<UUID> warehouseIds,
        @Param("quantity") java.math.BigDecimal quantity
    );

    @Query("""
        select b from StockBatch b
        where b.isDeleted = false
          and b.tenantId = :tenantId
          and b.materialId = :materialId
          and (:warehouseIdsEmpty = true or b.warehouseId in :warehouseIds)
          and b.quantity > :quantity
        order by b.receivedDate desc, b.createdAt desc
        """)
    List<StockBatch> findBatchesForValuationDesc(
        @Param("tenantId") String tenantId,
        @Param("materialId") UUID materialId,
        @Param("warehouseIdsEmpty") boolean warehouseIdsEmpty,
        @Param("warehouseIds") List<UUID> warehouseIds,
        @Param("quantity") java.math.BigDecimal quantity
    );

    @Query("""
        select b from StockBatch b
        where b.isDeleted = false
          and b.tenantId = :tenantId
          and b.materialId = :materialId
          and (:warehouseIdsEmpty = true or b.warehouseId in :warehouseIds)
          and exists (
              select 1 from StockBalance sb
              where sb.isDeleted = false
                and sb.tenantId = b.tenantId
                and sb.materialId = b.materialId
                and sb.warehouseId = b.warehouseId
                and sb.stockBatchId = b.id
                and sb.quantity > 0
          )
        order by b.receivedDate asc, b.createdAt asc
        """)
    List<StockBatch> findAvailableBatchesForValuationFifo(
        @Param("tenantId") String tenantId,
        @Param("materialId") UUID materialId,
        @Param("warehouseIdsEmpty") boolean warehouseIdsEmpty,
        @Param("warehouseIds") List<UUID> warehouseIds
    );

    @Query("""
        select b from StockBatch b
        where b.isDeleted = false
          and b.tenantId = :tenantId
          and b.materialId = :materialId
          and (:warehouseIdsEmpty = true or b.warehouseId in :warehouseIds)
          and exists (
              select 1 from StockBalance sb
              where sb.isDeleted = false
                and sb.tenantId = b.tenantId
                and sb.materialId = b.materialId
                and sb.warehouseId = b.warehouseId
                and sb.stockBatchId = b.id
                and sb.quantity > 0
          )
        order by b.receivedDate desc, b.createdAt desc
        """)
    List<StockBatch> findAvailableBatchesForValuationLifo(
        @Param("tenantId") String tenantId,
        @Param("materialId") UUID materialId,
        @Param("warehouseIdsEmpty") boolean warehouseIdsEmpty,
        @Param("warehouseIds") List<UUID> warehouseIds
    );
}
