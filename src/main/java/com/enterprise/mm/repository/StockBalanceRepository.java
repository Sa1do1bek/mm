package com.enterprise.mm.repository;
import com.enterprise.mm.entity.StockBalance;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface StockBalanceRepository extends JpaRepository<StockBalance, UUID> {
    /** Restrict the count before loading balances; material is needed for the snapshot line name. */
    @Query("""
        select b from StockBalance b left join fetch b.material m
        where b.tenantId = :tenantId and b.companyId = :companyId and b.isDeleted = false
          and (:allWarehouses = true or b.warehouseId in :warehouseIds)
          and (:locationId is null or b.storageLocationId = :locationId)
          and (:binId is null or b.storageBinId = :binId)
          and (:allMaterials = true or b.materialId in :materialIds or m.materialGroupId in :groupIds)
          and (:includeZeroBalances = true or b.quantity > 0)
        """)
    List<StockBalance> findInventorySnapshotBalances(
        @Param("tenantId") String tenantId, @Param("companyId") UUID companyId,
        @Param("allWarehouses") boolean allWarehouses, @Param("warehouseIds") List<UUID> warehouseIds,
        @Param("locationId") UUID locationId, @Param("binId") UUID binId,
        @Param("allMaterials") boolean allMaterials, @Param("materialIds") List<UUID> materialIds,
        @Param("groupIds") List<UUID> groupIds, @Param("includeZeroBalances") boolean includeZeroBalances);

    List<StockBalance> findByTenantIdAndCompanyIdAndWarehouseIdInAndIsDeletedFalse(
        String tenantId, UUID companyId, java.util.Collection<UUID> warehouseIds);
    @Query("""
        select distinct m.materialGroupId from StockBalance b
        join Material m on m.id=b.materialId and m.tenantId=b.tenantId and m.companyId=b.companyId
        where b.tenantId=:tenantId and b.companyId=:companyId and b.warehouseId=:warehouseId
          and b.isDeleted=false and b.quantity>0 and m.isDeleted=false and m.status='ACTIVE'
          and m.materialType=com.enterprise.mm.enums.MaterialType.TMB and m.materialGroupId is not null
        """)
    List<UUID> findReceiptStockGroups(@Param("tenantId") String tenantId, @Param("companyId") UUID companyId,
        @Param("warehouseId") UUID warehouseId);

    String TRANSFER_PICKING_QUERY = """
        select b from StockBalance b
        where b.tenantId = :tenantId and b.companyId = :companyId
          and b.warehouseId = :warehouseId and b.materialId in :materialIds
          and b.isDeleted = false
        order by b.materialId, b.stockBatchId, b.storageLocationId, b.storageBinId, b.id
        """;

    @Query(TRANSFER_PICKING_QUERY)
    List<StockBalance> findTransferPickingBalances(@Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId, @Param("warehouseId") UUID warehouseId,
        @Param("materialIds") List<UUID> materialIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query(TRANSFER_PICKING_QUERY)
    List<StockBalance> lockTransferPickingBalances(@Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId, @Param("warehouseId") UUID warehouseId,
        @Param("materialIds") List<UUID> materialIds);

    /** Date classifications apply to physical positive stock when explicitly filtering by status. */
    String EXPIRY_FILTER = """
        AND (:expiryStatusEmpty = true OR (e.quantity > 0 AND
            (CASE WHEN COALESCE(expiryBatch.expiresOn, expiryBatch.expirationDate) IS NULL THEN 'UNKNOWN'
                  WHEN COALESCE(expiryBatch.expiresOn, expiryBatch.expirationDate) < :expiryAsOfDate THEN 'EXPIRED'
                  WHEN COALESCE(expiryBatch.expiresOn, expiryBatch.expirationDate) = :expiryAsOfDate THEN 'EXPIRES_TODAY'
                  WHEN COALESCE(expiryBatch.expiresOn, expiryBatch.expirationDate) <= :expiryWarningThrough THEN 'EXPIRING_SOON'
                  ELSE 'VALID' END) IN :expiryStatuses))
        AND (:expiryWarning IS NULL OR :expiryWarning =
            (CASE WHEN e.quantity > 0 AND COALESCE(expiryBatch.expiresOn, expiryBatch.expirationDate) <= :expiryWarningThrough
                  THEN true ELSE false END))
        """;

    interface Material360AggregateProjection {
        BigDecimal getOnHand();
        BigDecimal getAvailable();
        BigDecimal getReserved();
        BigDecimal getQuarantine();
        BigDecimal getTotalValue();
        long getWarehouseCount();
        LocalDateTime getBalanceAt();
    }

    interface Material360AvailabilityProjection {
        UUID getId();
        UUID getWarehouseId();
        String getWarehouseName();
        UUID getStorageLocationId();
        String getStorageLocationName();
        UUID getStockBatchId();
        String getBatchNumber();
        BigDecimal getOnHandQuantity();
        BigDecimal getAvailableQuantity();
        BigDecimal getReservedQuantity();
        BigDecimal getQuarantineQuantity();
        BigDecimal getTotalValue();
        LocalDateTime getBalanceAt();
    }

    interface MaterialQuantityProjection {
        UUID getMaterialId();
        BigDecimal getQuantity();
    }

    interface ReportMaterialValueProjection {
        UUID getMaterialId();
        String getMaterialCode();
        String getMaterialName();
        BigDecimal getTotalValue();
    }

    interface WarehouseMaterialBalanceSummaryProjection {
        UUID getMaterialId();
        BigDecimal getTotalAvailable();
        BigDecimal getTotalOnHand();
        BigDecimal getTotalReserved();
    }

    @Query("""
        SELECT
            balance.materialId as materialId,
            COALESCE(SUM(balance.availableQuantity), 0) as totalAvailable,
            COALESCE(SUM(balance.quantity), 0) as totalOnHand,
            COALESCE(SUM(balance.reservedQuantity), 0) as totalReserved
        FROM StockBalance balance
        WHERE balance.tenantId = :tenantId
          AND balance.companyId = :companyId
          AND balance.warehouseId = :warehouseId
          AND balance.materialId IN :materialIds
          AND balance.isDeleted = false
        GROUP BY balance.materialId
        """)
    List<WarehouseMaterialBalanceSummaryProjection> findWarehouseBalancesByMaterialIds(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseId") UUID warehouseId,
        @Param("materialIds") List<UUID> materialIds
    );

    @Query("""
        SELECT b FROM StockBalance b
        JOIN FETCH b.stockBatch batch
        LEFT JOIN FETCH b.storageLocation loc
        LEFT JOIN FETCH b.storageBin bin
        WHERE b.tenantId = :tenantId
          AND b.companyId = :companyId
          AND b.warehouseId = :warehouseId
          AND b.materialId = :materialId
          AND b.isDeleted = false
          AND b.availableQuantity > 0
          AND b.status = 'ACTIVE'
          AND batch.status = 'ACTIVE'
          AND (COALESCE(batch.expiresOn, batch.expirationDate) IS NULL OR COALESCE(batch.expiresOn, batch.expirationDate) >= :today)
        ORDER BY b.availableQuantity DESC, b.id ASC
        """)
    List<StockBalance> findAvailablePositionsForIssue(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseId") UUID warehouseId,
        @Param("materialId") UUID materialId,
        @Param("today") LocalDate today
    );

    @Query("""
        select balance.materialId as materialId,
               coalesce(material.code, material.article) as materialCode,
               coalesce(material.name, material.code, material.article) as materialName,
               coalesce(sum(balance.totalValue), 0) as totalValue
        from StockBalance balance
        join balance.material material
        where balance.isDeleted = false
          and material.isDeleted = false
          and balance.tenantId = :tenantId
          and (:companyId is null or balance.companyId = :companyId)
        group by balance.materialId, material.code, material.article, material.name
        order by coalesce(sum(balance.totalValue), 0) desc, balance.materialId
        """)
    List<ReportMaterialValueProjection> findReportMaterialValues(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        Pageable pageable
    );

    @Query("""
        select coalesce(sum(balance.totalValue), 0)
        from StockBalance balance
        where balance.isDeleted = false
          and balance.tenantId = :tenantId
          and (:companyId is null or balance.companyId = :companyId)
        """)
    BigDecimal sumReportStockValue(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId
    );

    @Query("""
        select coalesce(sum(balance.totalValue), 0)
        from StockBalance balance
        where balance.isDeleted = false
          and balance.tenantId = :tenantId
          and exists (
              select 1
              from NonMovingStock stock
              where stock.isDeleted = false
                and stock.tenantId = balance.tenantId
                and stock.materialId = balance.materialId
                and stock.warehouseId = balance.warehouseId
                and stock.status <> 'CLOSED'
          )
        """)
    BigDecimal sumOpenNonMovingStockValueByTenant(@Param("tenantId") String tenantId);

    @Query("""
        select coalesce(sum(balance.totalValue), 0)
        from StockBalance balance
        where balance.isDeleted = false
          and balance.tenantId = :tenantId
          and (:companyId is null or balance.companyId = :companyId)
          and exists (
              select 1
              from NonMovingStock stock
              where stock.isDeleted = false
                and stock.tenantId = balance.tenantId
                and (:companyId is null or stock.companyId = :companyId)
                and stock.materialId = balance.materialId
                and stock.warehouseId = balance.warehouseId
                and stock.status <> 'CLOSED'
          )
        """)
    BigDecimal sumOpenNonMovingStockValueByTenantAndCompany(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId
    );

    Optional<StockBalance> findAnyById(UUID id);
    Optional<StockBalance> findByIdAndIsDeletedFalse(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select balance from StockBalance balance
        where balance.id = :id
          and balance.isDeleted = false
        """)
    Optional<StockBalance> findByIdAndIsDeletedFalseForUpdate(@Param("id") UUID id);

    @Query("""
        select coalesce(sum(balance.quantity), 0) as onHand,
               coalesce(sum(balance.availableQuantity), 0) as available,
               coalesce(sum(balance.reservedQuantity), 0) as reserved,
               coalesce(sum(balance.quarantineQuantity), 0) as quarantine,
               coalesce(sum(balance.totalValue), 0) as totalValue,
               count(distinct balance.warehouseId) as warehouseCount,
               max(balance.balanceAt) as balanceAt
        from StockBalance balance
        where balance.isDeleted = false
          and balance.tenantId = :tenantId
          and balance.companyId = :companyId
          and balance.materialId = :materialId
          and (:warehouseId is null or balance.warehouseId = :warehouseId)
        """)
    Material360AggregateProjection aggregateMaterial360(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId
    );

    @Query(
        value = """
            select balance.id as id,
                   balance.warehouseId as warehouseId,
                   warehouse.name as warehouseName,
                   balance.storageLocationId as storageLocationId,
                   storageLocation.name as storageLocationName,
                   balance.stockBatchId as stockBatchId,
                   stockBatch.batchNumber as batchNumber,
                   balance.quantity as onHandQuantity,
                   balance.availableQuantity as availableQuantity,
                   balance.reservedQuantity as reservedQuantity,
                   balance.quarantineQuantity as quarantineQuantity,
                   balance.totalValue as totalValue,
                   balance.balanceAt as balanceAt
            from StockBalance balance
            join balance.warehouse warehouse
            left join balance.storageLocation storageLocation
            left join balance.stockBatch stockBatch
            where balance.isDeleted = false
              and balance.tenantId = :tenantId
              and balance.companyId = :companyId
              and balance.materialId = :materialId
              and (:warehouseId is null or balance.warehouseId = :warehouseId)
              and (:storageLocationId is null or balance.storageLocationId = :storageLocationId)
              and (:stockBatchId is null or balance.stockBatchId = :stockBatchId)
              and (:positiveOnly = false or balance.availableQuantity > 0)
            order by balance.balanceAt desc, balance.id desc
            """,
        countQuery = """
            select count(balance)
            from StockBalance balance
            where balance.isDeleted = false
              and balance.tenantId = :tenantId
              and balance.companyId = :companyId
              and balance.materialId = :materialId
              and (:warehouseId is null or balance.warehouseId = :warehouseId)
              and (:storageLocationId is null or balance.storageLocationId = :storageLocationId)
              and (:stockBatchId is null or balance.stockBatchId = :stockBatchId)
              and (:positiveOnly = false or balance.availableQuantity > 0)
            """
    )
    Page<Material360AvailabilityProjection> findMaterial360Availability(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId,
        @Param("storageLocationId") UUID storageLocationId,
        @Param("stockBatchId") UUID stockBatchId,
        @Param("positiveOnly") boolean positiveOnly,
        Pageable pageable
    );
    @Query("""
        select b from StockBalance b
        where b.isDeleted = false
          and b.tenantId = :tenantId
          and b.material.id = :materialId
          and b.warehouse.id = :warehouseId
          and ((:storageLocationIdEmpty = true and b.storageLocation is null) or (:storageLocationIdEmpty = false and b.storageLocation.id = :storageLocationId))
          and ((:storageBinIdEmpty = true and b.storageBin is null) or (:storageBinIdEmpty = false and b.storageBin.id = :storageBinId))
          and ((:stockBatchIdEmpty = true and b.stockBatch is null) or (:stockBatchIdEmpty = false and b.stockBatch.id = :stockBatchId))
        """)
    Optional<StockBalance> findBalance(
        @Param("tenantId") String tenantId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId,
        @Param("storageLocationIdEmpty") boolean storageLocationIdEmpty,
        @Param("storageLocationId") UUID storageLocationId,
        @Param("storageBinIdEmpty") boolean storageBinIdEmpty,
        @Param("storageBinId") UUID storageBinId,
        @Param("stockBatchIdEmpty") boolean stockBatchIdEmpty,
        @Param("stockBatchId") UUID stockBatchId
    );

    default Optional<StockBalance> findBalance(
        String tenantId,
        UUID materialId,
        UUID warehouseId,
        boolean storageLocationIdEmpty,
        UUID storageLocationId,
        boolean stockBatchIdEmpty,
        UUID stockBatchId
    ) {
        return findBalance(tenantId, materialId, warehouseId, storageLocationIdEmpty, storageLocationId, true, null, stockBatchIdEmpty, stockBatchId);
    }

    @Query("""
        select b from StockBalance b
        where b.isDeleted = false
          and b.tenantId = :tenantId
          and b.companyId = :companyId
          and b.material.id = :materialId
          and b.warehouse.id = :warehouseId
          and ((:storageLocationIdEmpty = true and b.storageLocation is null) or (:storageLocationIdEmpty = false and b.storageLocation.id = :storageLocationId))
          and ((:storageBinIdEmpty = true and b.storageBin is null) or (:storageBinIdEmpty = false and b.storageBin.id = :storageBinId))
          and ((:stockBatchIdEmpty = true and b.stockBatch is null) or (:stockBatchIdEmpty = false and b.stockBatch.id = :stockBatchId))
        """)
    Optional<StockBalance> findCompanyBalance(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId,
        @Param("storageLocationIdEmpty") boolean storageLocationIdEmpty,
        @Param("storageLocationId") UUID storageLocationId,
        @Param("storageBinIdEmpty") boolean storageBinIdEmpty,
        @Param("storageBinId") UUID storageBinId,
        @Param("stockBatchIdEmpty") boolean stockBatchIdEmpty,
        @Param("stockBatchId") UUID stockBatchId
    );

    default Optional<StockBalance> findCompanyBalance(
        String tenantId,
        UUID companyId,
        UUID materialId,
        UUID warehouseId,
        boolean storageLocationIdEmpty,
        UUID storageLocationId,
        boolean stockBatchIdEmpty,
        UUID stockBatchId
    ) {
        return findCompanyBalance(tenantId, companyId, materialId, warehouseId, storageLocationIdEmpty, storageLocationId, true, null, stockBatchIdEmpty, stockBatchId);
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select b from StockBalance b
        where b.isDeleted = false
          and b.tenantId = :tenantId
          and b.material.id = :materialId
          and b.warehouse.id = :warehouseId
          and ((:storageLocationIdEmpty = true and b.storageLocation is null) or (:storageLocationIdEmpty = false and b.storageLocation.id = :storageLocationId))
          and ((:storageBinIdEmpty = true and b.storageBin is null) or (:storageBinIdEmpty = false and b.storageBin.id = :storageBinId))
          and ((:stockBatchIdEmpty = true and b.stockBatch is null) or (:stockBatchIdEmpty = false and b.stockBatch.id = :stockBatchId))
        """)
    Optional<StockBalance> findBalanceForUpdate(
        @Param("tenantId") String tenantId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId,
        @Param("storageLocationIdEmpty") boolean storageLocationIdEmpty,
        @Param("storageLocationId") UUID storageLocationId,
        @Param("storageBinIdEmpty") boolean storageBinIdEmpty,
        @Param("storageBinId") UUID storageBinId,
        @Param("stockBatchIdEmpty") boolean stockBatchIdEmpty,
        @Param("stockBatchId") UUID stockBatchId
    );

    default Optional<StockBalance> findBalanceForUpdate(
        String tenantId,
        UUID materialId,
        UUID warehouseId,
        boolean storageLocationIdEmpty,
        UUID storageLocationId,
        boolean stockBatchIdEmpty,
        UUID stockBatchId
    ) {
        return findBalanceForUpdate(tenantId, materialId, warehouseId, storageLocationIdEmpty, storageLocationId, true, null, stockBatchIdEmpty, stockBatchId);
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select b from StockBalance b
        where b.isDeleted = false
          and b.tenantId = :tenantId
          and b.companyId = :companyId
          and b.material.id = :materialId
          and b.warehouse.id = :warehouseId
          and ((:storageLocationIdEmpty = true and b.storageLocation is null) or (:storageLocationIdEmpty = false and b.storageLocation.id = :storageLocationId))
          and ((:storageBinIdEmpty = true and b.storageBin is null) or (:storageBinIdEmpty = false and b.storageBin.id = :storageBinId))
          and ((:stockBatchIdEmpty = true and b.stockBatch is null) or (:stockBatchIdEmpty = false and b.stockBatch.id = :stockBatchId))
        """)
    Optional<StockBalance> findCompanyBalanceForUpdate(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId,
        @Param("storageLocationIdEmpty") boolean storageLocationIdEmpty,
        @Param("storageLocationId") UUID storageLocationId,
        @Param("storageBinIdEmpty") boolean storageBinIdEmpty,
        @Param("storageBinId") UUID storageBinId,
        @Param("stockBatchIdEmpty") boolean stockBatchIdEmpty,
        @Param("stockBatchId") UUID stockBatchId
    );

    default Optional<StockBalance> findCompanyBalanceForUpdate(
        String tenantId,
        UUID companyId,
        UUID materialId,
        UUID warehouseId,
        boolean storageLocationIdEmpty,
        UUID storageLocationId,
        boolean stockBatchIdEmpty,
        UUID stockBatchId
    ) {
        return findCompanyBalanceForUpdate(tenantId, companyId, materialId, warehouseId, storageLocationIdEmpty, storageLocationId, true, null, stockBatchIdEmpty, stockBatchId);
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select b from StockBalance b
        where b.isDeleted = false
          and b.tenantId = :tenantId
          and b.companyId = :companyId
          and b.material.id = :materialId
          and b.warehouse.id = :warehouseId
          and ((:storageLocationIdEmpty = true and b.storageLocation is null) or (:storageLocationIdEmpty = false and b.storageLocation.id = :storageLocationId))
          and ((:storageBinIdEmpty = true and b.storageBin is null) or (:storageBinIdEmpty = false and b.storageBin.id = :storageBinId))
          and ((:stockBatchIdEmpty = true and b.stockBatch is null) or (:stockBatchIdEmpty = false and b.stockBatch.id = :stockBatchId))
          and ((:ownerPartyIdEmpty = true and b.ownerPartyId is null) or (:ownerPartyIdEmpty = false and b.ownerPartyId = :ownerPartyId))
          and b.stockCategory = :stockCategory
        """)
    Optional<StockBalance> findPartitionedBalanceForUpdate(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId,
        @Param("storageLocationIdEmpty") boolean storageLocationIdEmpty,
        @Param("storageLocationId") UUID storageLocationId,
        @Param("storageBinIdEmpty") boolean storageBinIdEmpty,
        @Param("storageBinId") UUID storageBinId,
        @Param("stockBatchIdEmpty") boolean stockBatchIdEmpty,
        @Param("stockBatchId") UUID stockBatchId,
        @Param("ownerPartyIdEmpty") boolean ownerPartyIdEmpty,
        @Param("ownerPartyId") UUID ownerPartyId,
        @Param("stockCategory") String stockCategory
    );

    default Optional<StockBalance> findPartitionedBalanceForUpdate(
        String tenantId,
        UUID companyId,
        UUID materialId,
        UUID warehouseId,
        UUID storageLocationId,
        UUID storageBinId,
        UUID stockBatchId,
        UUID ownerPartyId,
        String stockCategory
    ) {
        return findPartitionedBalanceForUpdate(
            tenantId,
            companyId,
            materialId,
            warehouseId,
            storageLocationId == null,
            storageLocationId,
            storageBinId == null,
            storageBinId,
            stockBatchId == null,
            stockBatchId,
            ownerPartyId == null,
            ownerPartyId,
            stockCategory != null ? stockCategory : "OWNED"
        );
    }

    @Query("""
        select b from StockBalance b
        where b.isDeleted = false
          and b.tenantId = :tenantId
          and b.companyId = :companyId
          and b.material.id = :materialId
          and b.warehouse.id = :warehouseId
          and ((:storageLocationIdEmpty = true and b.storageLocation is null) or (:storageLocationIdEmpty = false and b.storageLocation.id = :storageLocationId))
          and ((:storageBinIdEmpty = true and b.storageBin is null) or (:storageBinIdEmpty = false and b.storageBin.id = :storageBinId))
          and ((:stockBatchIdEmpty = true and b.stockBatch is null) or (:stockBatchIdEmpty = false and b.stockBatch.id = :stockBatchId))
          and ((:ownerPartyIdEmpty = true and b.ownerPartyId is null) or (:ownerPartyIdEmpty = false and b.ownerPartyId = :ownerPartyId))
          and b.stockCategory = :stockCategory
        """)
    Optional<StockBalance> findPartitionedBalance(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId,
        @Param("storageLocationIdEmpty") boolean storageLocationIdEmpty,
        @Param("storageLocationId") UUID storageLocationId,
        @Param("storageBinIdEmpty") boolean storageBinIdEmpty,
        @Param("storageBinId") UUID storageBinId,
        @Param("stockBatchIdEmpty") boolean stockBatchIdEmpty,
        @Param("stockBatchId") UUID stockBatchId,
        @Param("ownerPartyIdEmpty") boolean ownerPartyIdEmpty,
        @Param("ownerPartyId") UUID ownerPartyId,
        @Param("stockCategory") String stockCategory
    );

    default Optional<StockBalance> findPartitionedBalance(
        String tenantId,
        UUID companyId,
        UUID materialId,
        UUID warehouseId,
        UUID storageLocationId,
        UUID storageBinId,
        UUID stockBatchId,
        UUID ownerPartyId,
        String stockCategory
    ) {
        return findPartitionedBalance(
            tenantId,
            companyId,
            materialId,
            warehouseId,
            storageLocationId == null,
            storageLocationId,
            storageBinId == null,
            storageBinId,
            stockBatchId == null,
            stockBatchId,
            ownerPartyId == null,
            ownerPartyId,
            stockCategory != null ? stockCategory : "OWNED"
        );
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select b from StockBalance b
            where b.isDeleted = false
            and b.tenantId = :tenantId
            and b.companyId = :companyId
            and b.materialId = :materialId
            and b.warehouseId = :warehouseId
            and ((:storageLocationIdEmpty = true and b.storageLocation is null) or (:storageLocationIdEmpty = false and
            b.storageLocation.id = :storageLocationId))
            and ((:storageBinIdEmpty = true and b.storageBin is null) or
            (:storageBinIdEmpty = false and b.storageBin.id = :storageBinId))
            """)
    List<StockBalance> findCompanyBalancesForUpdate(
            @Param("tenantId") String tenantId,
            @Param("companyId") UUID companyId,
            @Param("materialId") UUID materialId,
            @Param("warehouseId") UUID warehouseId,
            @Param("storageLocationIdEmpty") boolean storageLocationIdEmpty,
            @Param("storageLocationId") UUID storageLocationId,
            @Param("storageBinIdEmpty") boolean storageBinIdEmpty,
            @Param("storageBinId") UUID storageBinId
    );

    List<StockBalance> findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(String tenantId, UUID materialId, UUID warehouseId);

    List<StockBalance> findByTenantIdAndWarehouse_IdAndIsDeletedFalse(String tenantId, UUID warehouseId);

    @Query("""
        select b from StockBalance b
        where b.isDeleted = false
          and b.tenantId = :tenantId
          and b.companyId = :companyId
          and b.warehouseId = :warehouseId
          and b.materialId in :materialIds
        """)
    List<StockBalance> findByTenantIdAndCompanyIdAndWarehouseIdAndMaterialIdInAndIsDeletedFalse(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseId") UUID warehouseId,
        @Param("materialIds") List<UUID> materialIds
    );

    List<StockBalance> findByTenantIdAndQuantityGreaterThanAndIsDeletedFalse(String tenantId, BigDecimal quantity);

    List<StockBalance> findByTenantIdAndIsDeletedFalse(String tenantId);

    List<StockBalance> findByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId);

    List<StockBalance> findByTenantIdAndCompanyIdAndOwnerPartyIdAndStockCategoryAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID ownerPartyId, String stockCategory
    );

    @Query("""
        select count(b) from StockBalance b
        where b.isDeleted = false
          and b.tenantId = :tenantId
          and b.companyId = :companyId
          and (:warehouseIdEmpty = true or b.warehouseId = :warehouseId)
          and b.quantity > 0
          and not exists (
              select 1 from InventoryCostLayer l
              where l.isDeleted = false
                and l.tenantId = b.tenantId
                and l.companyId = b.companyId
                and l.materialId = b.materialId
                and l.warehouseId = b.warehouseId
                and ((b.stockBatchId is null and l.stockBatchId is null) or l.stockBatchId = b.stockBatchId)
                and l.remainingQuantity > 0
                and l.status = 'ACTIVE'
          )
        """)
    long countPositiveBalancesWithoutActiveCostLayer(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseIdEmpty") boolean warehouseIdEmpty,
        @Param("warehouseId") UUID warehouseId
    );

    @Query("""
        select coalesce(sum(b.totalValue), 0) from StockBalance b
        where b.isDeleted = false
          and b.tenantId = :tenantId
          and b.companyId = :companyId
          and (:warehouseIdEmpty = true or b.warehouse.id = :warehouseId)
        """)
    BigDecimal sumTotalValue(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseIdEmpty") boolean warehouseIdEmpty,
        @Param("warehouseId") UUID warehouseId
    );

    @Query("""
        select coalesce(sum(b.availableQuantity), 0) from StockBalance b
        where b.isDeleted = false
          and b.tenantId = :tenantId
          and b.material.id = :materialId
        """)
    BigDecimal sumAvailableQuantityByMaterial(@Param("tenantId") String tenantId, @Param("materialId") UUID materialId);

    @Query("""
        select b.materialId as materialId, coalesce(sum(b.availableQuantity), 0) as quantity
        from StockBalance b
        where b.isDeleted = false
          and b.tenantId = :tenantId
          and b.companyId = :companyId
          and b.materialId in :materialIds
        group by b.materialId
        """)
    List<MaterialQuantityProjection> sumAvailableQuantityByMaterialIds(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialIds") List<UUID> materialIds
    );

    @Query("""
        select b.materialId as materialId, coalesce(sum(b.availableQuantity), 0) as quantity
        from StockBalance b
        where b.isDeleted = false
          and b.tenantId = :tenantId
          and b.companyId = :companyId
          and (:warehouseId is null or b.warehouseId = :warehouseId)
          and b.materialId in :materialIds
        group by b.materialId
        """)
    List<MaterialQuantityProjection> sumAvailableQuantityByMaterialIds(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseId") UUID warehouseId,
        @Param("materialIds") List<UUID> materialIds
    );

    @Query("""
        select coalesce(sum(b.quantity), 0) from StockBalance b
        where b.isDeleted = false
          and b.tenantId = :tenantId
          and b.companyId = :companyId
          and b.warehouse.id = :warehouseId
          and b.storageLocation.id = :storageLocationId
        """)
    BigDecimal sumQuantityByStorageLocation(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseId") UUID warehouseId,
        @Param("storageLocationId") UUID storageLocationId
    );

    @Query("""
        select count(distinct b.material.id) from StockBalance b
        where b.isDeleted = false
          and b.tenantId = :tenantId
          and b.companyId = :companyId
          and b.warehouse.id = :warehouseId
          and b.storageLocation.id = :storageLocationId
          and b.quantity > 0
          and b.material.id <> :materialId
        """)
    long countOtherMaterialsInStorageLocation(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseId") UUID warehouseId,
        @Param("storageLocationId") UUID storageLocationId,
        @Param("materialId") UUID materialId
    );

    @Query("""
        select distinct b.tenantId from StockBalance b
        where b.isDeleted = false
          and b.quantity > 0
        """)
    List<String> findDistinctTenantIdsWithPositiveStock();

    @Query("""
        SELECT e
        FROM StockBalance e
        LEFT JOIN e.stockBatch expiryBatch
        WHERE
            e.tenantId = :tenantId
            AND (:companyId IS NULL OR e.companyId = :companyId)
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (:search IS NULL OR
                 LOWER(COALESCE(e.code, '')) LIKE CONCAT('%', LOWER(CAST(:search AS string)), '%')
                 OR LOWER(COALESCE(e.name, '')) LIKE CONCAT('%', LOWER(CAST(:search AS string)), '%'))
            AND (CAST(:dateFrom AS timestamp) IS NULL OR e.createdAt >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR e.createdAt <= :dateTo)
            AND (:statusEmpty = true OR e.status IN :status)
            AND (:warehouseId IS NULL OR e.warehouseId = :warehouseId)
            AND (:stockBatchId IS NULL OR e.stockBatchId = :stockBatchId)
            AND (:materialGroupId IS NULL OR e.material.materialGroupId = :materialGroupId)
            AND (:materialIdsEmpty = true OR e.materialId IN :materialIds)
            AND (:id IS NULL OR e.id = :id)
            AND (:code IS NULL OR LOWER(COALESCE(e.code, '')) LIKE CONCAT('%', LOWER(CAST(:code AS string)), '%'))
            AND (:name IS NULL OR LOWER(COALESCE(e.name, '')) LIKE CONCAT('%', LOWER(CAST(:name AS string)), '%'))
            AND (:description IS NULL OR LOWER(COALESCE(e.description, '')) LIKE CONCAT('%', LOWER(CAST(:description AS string)), '%'))
            AND (:deleted IS NULL OR e.isDeleted = :deleted)
            AND (:createdBy IS NULL OR LOWER(COALESCE(e.createdBy, '')) LIKE CONCAT('%', LOWER(CAST(:createdBy AS string)), '%'))
            AND (:updatedBy IS NULL OR LOWER(COALESCE(e.updatedBy, '')) LIKE CONCAT('%', LOWER(CAST(:updatedBy AS string)), '%'))
            AND (:version IS NULL OR e.version = :version)
            AND (:materialId IS NULL OR e.materialId = :materialId)
            AND (:storageLocationId IS NULL OR e.storageLocationId = :storageLocationId)
            AND (:storageBinId IS NULL OR e.storageBinId = :storageBinId)
            AND (:quantity IS NULL OR e.quantity = :quantity)
            AND (:availableQuantity IS NULL OR e.availableQuantity = :availableQuantity)
            AND (:reservedQuantity IS NULL OR e.reservedQuantity = :reservedQuantity)
            AND (:quarantineQuantity IS NULL OR e.quarantineQuantity = :quarantineQuantity)
            AND (:totalValue IS NULL OR e.totalValue = :totalValue)
            AND (CAST(:balanceAt AS timestamp) IS NULL OR e.balanceAt = :balanceAt)
        """ + EXPIRY_FILTER)
    Page<StockBalance> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("warehouseId") UUID warehouseId,
        @Param("stockBatchId") UUID stockBatchId,
        @Param("materialGroupId") UUID materialGroupId,
        @Param("materialIds") List<UUID> materialIds,
        @Param("materialIdsEmpty") boolean materialIdsEmpty,
        @Param("id") UUID id,
        @Param("code") String code,
        @Param("name") String name,
        @Param("description") String description,
        @Param("deleted") Boolean deleted,
        @Param("createdBy") String createdBy,
        @Param("updatedBy") String updatedBy,
        @Param("version") Long version,
        @Param("materialId") UUID materialId,
        @Param("storageLocationId") UUID storageLocationId,
        @Param("storageBinId") UUID storageBinId,
        @Param("quantity") BigDecimal quantity,
        @Param("availableQuantity") BigDecimal availableQuantity,
        @Param("reservedQuantity") BigDecimal reservedQuantity,
        @Param("quarantineQuantity") BigDecimal quarantineQuantity,
        @Param("totalValue") BigDecimal totalValue,
        @Param("balanceAt") LocalDateTime balanceAt,
        @Param("expiryStatuses") List<String> expiryStatuses,
        @Param("expiryStatusEmpty") boolean expiryStatusEmpty,
        @Param("expiryWarning") Boolean expiryWarning,
        @Param("expiryAsOfDate") LocalDate expiryAsOfDate,
        @Param("expiryWarningThrough") LocalDate expiryWarningThrough,
        Pageable pageable
    );

    interface Warehouse360AggregateProjection {
        BigDecimal getOnHand();
        BigDecimal getAvailable();
        BigDecimal getReserved();
        BigDecimal getQuarantine();
        BigDecimal getTotalValue();
        long getMaterialCount();
        long getPositiveBalanceCount();
        LocalDateTime getBalanceAt();
    }

    interface Warehouse360BalanceProjection {
        LocalDate getExpirationDate();
        UUID getId();
        UUID getMaterialId();
        String getMaterialCode();
        String getMaterialName();
        UUID getStorageLocationId();
        String getStorageLocationName();
        UUID getStockBatchId();
        String getBatchNumber();
        BigDecimal getOnHandQuantity();
        BigDecimal getAvailableQuantity();
        BigDecimal getReservedQuantity();
        BigDecimal getQuarantineQuantity();
        BigDecimal getTotalValue();
        LocalDateTime getBalanceAt();
    }

    @Query("""
        select coalesce(sum(balance.quantity), 0) as onHand,
               coalesce(sum(balance.availableQuantity), 0) as available,
               coalesce(sum(balance.reservedQuantity), 0) as reserved,
               coalesce(sum(balance.quarantineQuantity), 0) as quarantine,
               coalesce(sum(balance.totalValue), 0) as totalValue,
               count(distinct balance.materialId) as materialCount,
               coalesce(sum(case when balance.quantity > 0 then 1 else 0 end), 0) as positiveBalanceCount,
               max(balance.balanceAt) as balanceAt
        from StockBalance balance
        where balance.isDeleted = false
          and balance.tenantId = :tenantId
          and balance.companyId = :companyId
          and balance.warehouseId = :warehouseId
        """)
    Warehouse360AggregateProjection aggregateWarehouse360(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseId") UUID warehouseId
    );

    @Query(
        value = """
            select e.id as id,
                   e.materialId as materialId,
                   coalesce(material.code, material.article) as materialCode,
                   coalesce(material.name, material.code, material.article) as materialName,
                   e.storageLocationId as storageLocationId,
                   storageLocation.name as storageLocationName,
                   e.stockBatchId as stockBatchId,
                   expiryBatch.batchNumber as batchNumber,
                   COALESCE(expiryBatch.expiresOn, expiryBatch.expirationDate) as expirationDate,
                   e.quantity as onHandQuantity,
                   e.availableQuantity as availableQuantity,
                   e.reservedQuantity as reservedQuantity,
                   e.quarantineQuantity as quarantineQuantity,
                   e.totalValue as totalValue,
                   e.balanceAt as balanceAt
            from StockBalance e
            join e.material material
            left join e.storageLocation storageLocation
            left join e.stockBatch expiryBatch
            where e.isDeleted = false
              and e.tenantId = :tenantId
              and e.companyId = :companyId
              and e.warehouseId = :warehouseId
              and (:storageLocationId is null or e.storageLocationId = :storageLocationId)
              and (:positiveOnly = false or e.availableQuantity > 0 or e.quantity > 0)
            """ + EXPIRY_FILTER + """
            order by e.availableQuantity desc, material.code asc, e.id desc
            """,
        countQuery = """
            select count(e)
            from StockBalance e
            left join e.stockBatch expiryBatch
            where e.isDeleted = false
              and e.tenantId = :tenantId
              and e.companyId = :companyId
              and e.warehouseId = :warehouseId
              and (:storageLocationId is null or e.storageLocationId = :storageLocationId)
              and (:positiveOnly = false or e.availableQuantity > 0 or e.quantity > 0)
            """ + EXPIRY_FILTER
    )
    Page<Warehouse360BalanceProjection> findWarehouse360Balances(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseId") UUID warehouseId,
        @Param("storageLocationId") UUID storageLocationId,
        @Param("positiveOnly") boolean positiveOnly,
        @Param("expiryStatuses") List<String> expiryStatuses,
        @Param("expiryStatusEmpty") boolean expiryStatusEmpty,
        @Param("expiryWarning") Boolean expiryWarning,
        @Param("expiryAsOfDate") LocalDate expiryAsOfDate,
        @Param("expiryWarningThrough") LocalDate expiryWarningThrough,
        Pageable pageable
    );

    @Query("""
        select b from StockBalance b
        where b.isDeleted = false
          and b.tenantId = :tenantId
          and b.material.id = :materialId
          and (:warehouseIdsEmpty = true or b.warehouse.id in :warehouseIds)
        """)
    List<StockBalance> findBalancesForValuation(
        @Param("tenantId") String tenantId,
        @Param("materialId") UUID materialId,
        @Param("warehouseIdsEmpty") boolean warehouseIdsEmpty,
        @Param("warehouseIds") List<UUID> warehouseIds
    );

}
