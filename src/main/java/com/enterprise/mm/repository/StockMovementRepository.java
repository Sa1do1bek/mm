package com.enterprise.mm.repository;

import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.enums.MovementType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {
    @EntityGraph(attributePaths = {
        "material", "material.unitOfMeasure", "warehouse", "targetWarehouse", "storageLocation", "storageBin",
        "targetStorageLocation", "targetStorageBin", "stockBatch", "reversalOf"
    })
    @Query("select e from StockMovement e where e.tenantId = :tenantId and e.companyId = :companyId and e.id in :ids")
    List<StockMovement> findQueryRows(@Param("tenantId") String tenantId, @Param("companyId") UUID companyId, @Param("ids") List<UUID> ids);

    @Query(value = """
        select coalesce(sum(m.quantity * coalesce(b.contract_quantity_per_stock_quantity, 1)), 0)
        from stock_movements m
        left join contract_material_links b on b.tenant_id = m.tenant_id and b.company_id = m.company_id
            and b.specification_id = m.contract_specification_id and b.material_id = m.material_id
        where m.tenant_id = :tenantId and m.company_id = :companyId
          and m.contract_specification_id = :specificationId
          and m.is_delete = false and m.status = 'POSTED' and m.movement_type = 'RECEIPT'
          and m.reversal_of_id is null
          and not exists (select r.id from stock_movements r
              where r.reversal_of_id = m.id and r.tenant_id = :tenantId and r.company_id = :companyId
                and r.is_delete = false and r.status = 'POSTED')
        """, nativeQuery = true)
    BigDecimal sumEffectiveContractReceipts(@Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId, @Param("specificationId") UUID specificationId);

    boolean existsByTenantIdAndCompanyIdAndContractSpecificationIdAndMaterialIdAndMovementType(
        String tenantId, UUID companyId, UUID contractSpecificationId, UUID materialId, MovementType movementType);

    List<StockMovement> findByTenantIdAndCompanyIdAndReferenceIdOrderByMovementDateAscCreatedAtAscIdAsc(String tenantId, UUID companyId, UUID referenceId);

    interface Material360MovementProjection {
        UUID getId();
        LocalDateTime getPostedAt();
        LocalDate getMovementDate();
        MovementType getMovementType();
        String getStatus();
        BigDecimal getQuantity();
        String getUnitOfMeasureCode();
        String getUnitOfMeasureShortName();
        UUID getWarehouseId();
        String getWarehouseName();
        UUID getStockBatchId();
        String getBatchNumber();
        String getReferenceType();
        UUID getReferenceId();
    }

    interface MaterialQuantityProjection {
        UUID getMaterialId();
        BigDecimal getQuantity();
    }

    interface ReportMonthlyIssueProjection {
        UUID getMaterialId();
        Integer getIssueYear();
        Integer getIssueMonth();
        BigDecimal getQuantity();
    }

    @Query("""
        select movement.materialId as materialId,
               year(movement.postedAt) as issueYear,
               month(movement.postedAt) as issueMonth,
               coalesce(sum(movement.quantity), 0) as quantity
        from StockMovement movement
        where movement.isDeleted = false
          and movement.tenantId = :tenantId
          and (:companyId is null or movement.companyId = :companyId)
          and movement.status in :statuses
          and movement.movementType in :movementTypes
          and movement.postedAt between :periodStart and :periodEnd
          and movement.materialId in :materialIds
        group by movement.materialId, year(movement.postedAt), month(movement.postedAt)
        """)
    List<ReportMonthlyIssueProjection> sumMonthlyIssueByMaterialIds(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("statuses") List<String> statuses,
        @Param("movementTypes") List<MovementType> movementTypes,
        @Param("periodStart") LocalDateTime periodStart,
        @Param("periodEnd") LocalDateTime periodEnd,
        @Param("materialIds") List<UUID> materialIds
    );

    Optional<StockMovement> findAnyById(UUID id);
    Optional<StockMovement> findByIdAndIsDeletedFalse(UUID id);
    boolean existsByTenantIdAndCompanyIdAndReversalOfIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID reversalOfId
    );

    @Query(
        value = """
            select movement.id as id,
                   movement.postedAt as postedAt,
                   movement.movementDate as movementDate,
                   movement.movementType as movementType,
                   movement.status as status,
                   movement.quantity as quantity,
                   unitOfMeasure.code as unitOfMeasureCode,
                   unitOfMeasure.measurementNameShort as unitOfMeasureShortName,
                   movement.warehouseId as warehouseId,
                   warehouse.name as warehouseName,
                   movement.stockBatchId as stockBatchId,
                   coalesce(movement.batchNumber, stockBatch.batchNumber) as batchNumber,
                   movement.referenceType as referenceType,
                   movement.referenceId as referenceId
            from StockMovement movement
            join movement.warehouse warehouse
            left join movement.material material
            left join material.unitOfMeasure unitOfMeasure
              on unitOfMeasure.tenantId = :tenantId and unitOfMeasure.companyId = :companyId and unitOfMeasure.isDeleted = false
            left join movement.stockBatch stockBatch
            where movement.isDeleted = false
              and movement.tenantId = :tenantId
              and movement.companyId = :companyId
              and movement.materialId = :materialId
              and movement.status in :statuses
              and (:dateFrom is null or movement.movementDate >= :dateFrom)
              and (:dateTo is null or movement.movementDate <= :dateTo)
              and (:warehouseId is null or movement.warehouseId = :warehouseId)
              and (:movementType is null or movement.movementType = :movementType)
              and (:stockBatchId is null or movement.stockBatchId = :stockBatchId)
            order by movement.postedAt desc, movement.id desc
            """,
        countQuery = """
            select count(movement)
            from StockMovement movement
            where movement.isDeleted = false
              and movement.tenantId = :tenantId
              and movement.companyId = :companyId
              and movement.materialId = :materialId
              and movement.status in :statuses
              and (:dateFrom is null or movement.movementDate >= :dateFrom)
              and (:dateTo is null or movement.movementDate <= :dateTo)
              and (:warehouseId is null or movement.warehouseId = :warehouseId)
              and (:movementType is null or movement.movementType = :movementType)
              and (:stockBatchId is null or movement.stockBatchId = :stockBatchId)
            """
    )
    Page<Material360MovementProjection> findMaterial360Movements(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialId") UUID materialId,
        @Param("statuses") List<String> statuses,
        @Param("dateFrom") LocalDate dateFrom,
        @Param("dateTo") LocalDate dateTo,
        @Param("warehouseId") UUID warehouseId,
        @Param("movementType") MovementType movementType,
        @Param("stockBatchId") UUID stockBatchId,
        Pageable pageable
    );
    List<StockMovement> findByTenantIdAndDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(String tenantId, UUID documentId);

    void deleteByTenantIdAndDocumentId(String tenantId, UUID documentId);

    List<StockMovement> findByTenantIdAndReferenceTypeAndReferenceIdAndIsDeletedFalse(String tenantId, String referenceType, UUID referenceId);
    Optional<StockMovement> findFirstByTenantIdAndMaterial_IdAndWarehouse_IdAndStatusAndIsDeletedFalseOrderByPostedAtDesc(String tenantId, UUID materialId, UUID warehouseId, String status);
    Optional<StockMovement> findFirstByTenantIdAndMaterial_IdAndWarehouse_IdAndStatusInAndIsDeletedFalseOrderByPostedAtDesc(String tenantId, UUID materialId, UUID warehouseId, List<String> statuses);
    Optional<StockMovement> findFirstByTenantIdAndCompanyIdAndMaterial_IdAndWarehouse_IdAndStatusInAndIsDeletedFalseOrderByPostedAtDesc(
        String tenantId,
        UUID companyId,
        UUID materialId,
        UUID warehouseId,
        List<String> statuses
    );
    List<StockMovement> findByTenantIdAndMaterial_IdAndWarehouse_IdAndStatusAndPostedAtBeforeAndIsDeletedFalse(String tenantId, UUID materialId, UUID warehouseId, String status, LocalDateTime before);
    List<StockMovement> findByTenantIdAndMaterial_IdAndWarehouse_IdAndStatusAndIsDeletedFalseOrderByPostedAtAsc(String tenantId, UUID materialId, UUID warehouseId, String status);
    List<StockMovement> findByTenantIdAndMaterial_IdAndWarehouse_IdAndStatusInAndIsDeletedFalseOrderByPostedAtAsc(String tenantId, UUID materialId, UUID warehouseId, List<String> statuses);
    List<StockMovement> findByTenantIdAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId);
    List<StockMovement> findByTenantIdAndCompanyIdAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, UUID companyId);
    List<StockMovement> findByTenantIdAndStatusAndMovementTypeInAndPostedAtAfterAndIsDeletedFalse(String tenantId, String status, List<MovementType> movementTypes, LocalDateTime postedAt);
    List<StockMovement> findByTenantIdAndCompanyIdAndStatusAndMovementTypeInAndPostedAtAfterAndIsDeletedFalse(String tenantId, UUID companyId, String status, List<MovementType> movementTypes, LocalDateTime postedAt);
    List<StockMovement> findByTenantIdAndStatusInAndMovementTypeInAndPostedAtAfterAndIsDeletedFalse(String tenantId, List<String> statuses, List<MovementType> movementTypes, LocalDateTime postedAt);
    List<StockMovement> findByTenantIdAndCompanyIdAndStatusInAndMovementTypeInAndPostedAtAfterAndIsDeletedFalse(String tenantId, UUID companyId, List<String> statuses, List<MovementType> movementTypes, LocalDateTime postedAt);

    @Query("""
        select movement from StockMovement movement
        where movement.isDeleted = false
          and movement.tenantId = :tenantId
          and (:companyId is null or movement.companyId = :companyId)
          and movement.referenceType = :referenceType
          and movement.movementType = :movementType
          and movement.status = :status
          and movement.postedAt between :periodStart and :periodEnd
        order by movement.postedAt, movement.id
        """)
    List<StockMovement> findPostedPurchaseOrderReceipts(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("referenceType") String referenceType,
        @Param("movementType") MovementType movementType,
        @Param("status") String status,
        @Param("periodStart") LocalDateTime periodStart,
        @Param("periodEnd") LocalDateTime periodEnd,
        Pageable pageable
    );

    @Query("""
        select m.materialId as materialId, coalesce(sum(m.quantity), 0) as quantity
        from StockMovement m
        where m.isDeleted = false
          and m.tenantId = :tenantId
          and m.companyId = :companyId
          and m.status in :statuses
          and m.movementType in :movementTypes
          and m.postedAt >= :postedAt
          and m.materialId in :materialIds
        group by m.materialId
        """)
    List<MaterialQuantityProjection> sumConsumptionQuantityByMaterialIds(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("statuses") List<String> statuses,
        @Param("movementTypes") List<MovementType> movementTypes,
        @Param("postedAt") LocalDateTime postedAt,
        @Param("materialIds") List<UUID> materialIds
    );

    @Query("""
        select m.materialId as materialId, coalesce(sum(m.quantity), 0) as quantity
        from StockMovement m
        where m.isDeleted = false
          and m.tenantId = :tenantId
          and m.companyId = :companyId
          and (:warehouseId is null or m.warehouseId = :warehouseId)
          and m.status in :statuses
          and m.movementType in :movementTypes
          and m.postedAt >= :postedAt
          and m.materialId in :materialIds
        group by m.materialId
        """)
    List<MaterialQuantityProjection> sumConsumptionQuantityByMaterialIds(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseId") UUID warehouseId,
        @Param("statuses") List<String> statuses,
        @Param("movementTypes") List<MovementType> movementTypes,
        @Param("postedAt") LocalDateTime postedAt,
        @Param("materialIds") List<UUID> materialIds
    );

    @Query("""
        select count(m) from StockMovement m
        where m.isDeleted = false
          and m.tenantId = :tenantId
          and m.companyId = :companyId
          and m.status = 'POSTED'
          and m.postedAt between :periodStart and :periodEnd
          and (:warehouseIdEmpty = true or m.warehouse.id = :warehouseId or m.targetWarehouse.id = :warehouseId)
        """)
    long countPostedInPeriod(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseIdEmpty") boolean warehouseIdEmpty,
        @Param("warehouseId") UUID warehouseId,
        @Param("periodStart") LocalDateTime periodStart,
        @Param("periodEnd") LocalDateTime periodEnd
    );

    @Query("""
        select count(m) from StockMovement m
        where m.isDeleted = false
          and m.tenantId = :tenantId
          and m.companyId = :companyId
          and m.status not in ('POSTED', 'CANCELLED')
          and m.movementDate between :periodStart and :periodEnd
          and (:warehouseIdEmpty = true or m.warehouse.id = :warehouseId or m.targetWarehouse.id = :warehouseId)
        """)
    long countOpenInPeriod(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseIdEmpty") boolean warehouseIdEmpty,
        @Param("warehouseId") UUID warehouseId,
        @Param("periodStart") LocalDate periodStart,
        @Param("periodEnd") LocalDate periodEnd
    );

    @Query("""
        select count(m) from StockMovement m
        where m.isDeleted = false
          and m.tenantId = :tenantId
          and m.companyId = :companyId
          and m.status = 'POSTED'
          and m.movementType in :movementTypes
          and coalesce(m.unitCost, 0) <= 0
          and not (
            m.unitCost = 0 and m.quantity > 0 and m.totalCost >= 0
            and m.referenceType = 'OPENING_STOCK_EXACT' and m.referenceId is not null
            and exists (
              select r.id from ExactOpeningRow r, ExactOpeningImport i, InventoryCostLayer l, StockCostMovement c
              where r.movementId = m.id and r.importId = m.referenceId
                and r.quantity = m.quantity and r.totalValue = m.totalCost
                and i.id = r.importId and i.tenantId = m.tenantId and i.companyId = m.companyId
                and i.status = 'COMPLETED'
                and l.isDeleted = false and l.authoritativeValue = true
                and l.tenantId = m.tenantId and l.companyId = m.companyId
                and l.materialId = m.materialId and l.warehouseId = m.warehouseId
                and (l.stockBatchId = m.stockBatchId or (l.stockBatchId is null and m.stockBatchId is null))
                and l.sourceMovementId = m.id and l.sourceDocumentType = 'OPENING_STOCK_EXACT'
                and l.sourceDocumentId = m.referenceId and l.receivedQuantity = m.quantity and l.totalCost >= 0
                and c.isDeleted = false and c.reversal = false and c.movementType = 'OPENING_BALANCE'
                and c.tenantId = m.tenantId and c.companyId = m.companyId
                and c.materialId = m.materialId and c.warehouseId = m.warehouseId
                and c.stockMovementId = m.id and c.costLayerId = l.id
                and c.sourceDocumentType = 'OPENING_STOCK_EXACT' and c.sourceDocumentId = m.referenceId
                and c.quantity = m.quantity and c.totalCost = m.totalCost
            )
          )
          and m.postedAt between :periodStart and :periodEnd
          and (:warehouseIdEmpty = true or m.warehouseId = :warehouseId)
        """)
    long countZeroCostInboundPostedInPeriod(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseIdEmpty") boolean warehouseIdEmpty,
        @Param("warehouseId") UUID warehouseId,
        @Param("movementTypes") List<MovementType> movementTypes,
        @Param("periodStart") LocalDateTime periodStart,
        @Param("periodEnd") LocalDateTime periodEnd
    );

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {
        "material", "material.unitOfMeasure", "warehouse", "targetWarehouse", "storageLocation", "storageBin",
        "targetStorageLocation", "targetStorageBin", "stockBatch", "reversalOf"
    })
    @Query("""
        SELECT e
        FROM StockMovement e
        LEFT JOIN e.material material
        WHERE
            e.tenantId = :tenantId
            AND (:companyId IS NULL OR e.companyId = :companyId)
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (:search IS NULL OR
                 LOWER(COALESCE(e.code, '')) LIKE :search
                 OR LOWER(COALESCE(e.name, '')) LIKE :search
                 OR LOWER(COALESCE(e.description, '')) LIKE :search
                 OR LOWER(COALESCE(material.code, '')) LIKE :search
                 OR LOWER(COALESCE(material.name, '')) LIKE :search)
            AND (CAST(:dateFrom AS date) IS NULL OR COALESCE(e.movementDate, CAST(e.createdAt AS date)) >= :dateFrom)
            AND (CAST(:dateTo AS date) IS NULL OR COALESCE(e.movementDate, CAST(e.createdAt AS date)) <= :dateTo)
            AND (CAST(:movementDate AS date) IS NULL OR COALESCE(e.movementDate, CAST(e.createdAt AS date)) = :movementDate)
            AND (:statusEmpty = true OR e.status IN :status)
            AND (:warehouseId IS NULL OR e.warehouseId = :warehouseId OR e.targetWarehouseId = :warehouseId)
            AND (:warehouseIdsEmpty = true OR e.warehouseId IN :warehouseIds OR e.targetWarehouseId IN :warehouseIds)
            AND (:materialId IS NULL OR e.materialId = :materialId)
            AND (:materialIdsEmpty = true OR e.materialId IN :materialIds)
            AND (:materialCode IS NULL OR LOWER(COALESCE(material.code, '')) LIKE :materialCode)
            AND (:materialName IS NULL OR LOWER(COALESCE(material.name, '')) LIKE :materialName)
            AND (:inventoryId IS NULL OR e.sourceInventoryItemId = :inventoryId)
            AND (:referenceId IS NULL OR e.referenceId = :referenceId)
            AND (:referenceType IS NULL OR UPPER(e.referenceType) = :referenceType)
            AND (:movementType IS NULL OR e.movementType = :movementType)
            AND (:movementTypesEmpty = true OR e.movementType IN :movementTypes)
        """)
    Page<StockMovement> searchFiltered(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDate dateFrom,
        @Param("dateTo") LocalDate dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("warehouseId") UUID warehouseId,
        @Param("materialIds") List<UUID> materialIds,
        @Param("materialIdsEmpty") boolean materialIdsEmpty,
        @Param("inventoryId") UUID inventoryId,
        @Param("referenceId") UUID referenceId,
        @Param("referenceType") String referenceType,
        @Param("movementType") MovementType movementType,
        @Param("movementDate") LocalDate movementDate,
        @Param("warehouseIds") List<UUID> warehouseIds,
        @Param("warehouseIdsEmpty") boolean warehouseIdsEmpty,
        @Param("materialId") UUID materialId,
        @Param("materialCode") String materialCode,
        @Param("materialName") String materialName,
        @Param("movementTypes") List<MovementType> movementTypes,
        @Param("movementTypesEmpty") boolean movementTypesEmpty,
        Pageable pageable
    );

    // Keep nullable parameters typed by direct comparisons; casts/functions alone can bind them as bytea on PostgreSQL.
    default Page<StockMovement> search(
        String tenantId, UUID companyId, boolean includeDeleted, String search,
        LocalDateTime dateFrom, LocalDateTime dateTo, List<String> status, boolean statusEmpty,
        UUID warehouseId, List<UUID> materialIds, boolean materialIdsEmpty, UUID inventoryId,
        UUID referenceId, String referenceType, MovementType movementType, LocalDate movementDate,
        List<UUID> warehouseIds, boolean warehouseIdsEmpty, UUID materialId, String materialCode,
        String materialName, List<MovementType> movementTypes, boolean movementTypesEmpty, Pageable pageable
    ) {
        return searchFiltered(tenantId, companyId, includeDeleted, movementSearchPattern(search),
            dateFrom == null ? null : dateFrom.toLocalDate(), dateTo == null ? null : dateTo.toLocalDate(),
            status, statusEmpty, warehouseId, materialIds, materialIdsEmpty, inventoryId, referenceId,
            referenceType, movementType, movementDate, warehouseIds, warehouseIdsEmpty, materialId,
            movementSearchPattern(materialCode), movementSearchPattern(materialName), movementTypes,
            movementTypesEmpty, pageable);
    }

    private static String movementSearchPattern(String value) {
        return value == null || value.isBlank() ? null : "%" + value.trim().toLowerCase(java.util.Locale.ROOT) + "%";
    }

    default Page<StockMovement> search(
        String tenantId,
        UUID companyId,
        boolean includeDeleted,
        String search,
        LocalDateTime dateFrom,
        LocalDateTime dateTo,
        List<String> status,
        boolean statusEmpty,
        UUID warehouseId,
        List<UUID> materialIds,
        boolean materialIdsEmpty,
        UUID inventoryId,
        UUID referenceId,
        String referenceType,
        MovementType movementType,
        Pageable pageable
    ) {
        return search(
            tenantId,
            companyId,
            includeDeleted,
            search,
            dateFrom,
            dateTo,
            status,
            statusEmpty,
            warehouseId,
            materialIds,
            materialIdsEmpty,
            inventoryId,
            referenceId,
            referenceType,
            movementType,
            null,
            null,
            true,
            null,
            null,
            null,
            null,
            true,
            pageable
        );
    }

    interface Warehouse360MovementProjection {
        UUID getId();
        LocalDateTime getPostedAt();
        LocalDate getMovementDate();
        MovementType getMovementType();
        String getStatus();
        BigDecimal getQuantity();
        String getUnitOfMeasureCode();
        String getUnitOfMeasureShortName();
        UUID getMaterialId();
        String getMaterialCode();
        String getMaterialName();
        String getBatchNumber();
        String getReferenceType();
        UUID getReferenceId();
    }

    @Query(
        value = """
            select movement.id as id,
                   movement.postedAt as postedAt,
                   movement.movementDate as movementDate,
                   movement.movementType as movementType,
                   movement.status as status,
                   movement.quantity as quantity,
                   unitOfMeasure.code as unitOfMeasureCode,
                   unitOfMeasure.measurementNameShort as unitOfMeasureShortName,
                   movement.materialId as materialId,
                   coalesce(material.code, material.article) as materialCode,
                   coalesce(material.name, material.code, material.article) as materialName,
                   coalesce(movement.batchNumber, stockBatch.batchNumber) as batchNumber,
                   movement.referenceType as referenceType,
                   movement.referenceId as referenceId
            from StockMovement movement
            left join movement.material material
            left join material.unitOfMeasure unitOfMeasure
              on unitOfMeasure.tenantId = :tenantId and unitOfMeasure.companyId = :companyId and unitOfMeasure.isDeleted = false
            left join movement.stockBatch stockBatch
            where movement.isDeleted = false
              and movement.tenantId = :tenantId
              and movement.companyId = :companyId
              and movement.warehouseId = :warehouseId
              and movement.status in :statuses
              and (:dateFrom is null or movement.movementDate >= :dateFrom)
              and (:dateTo is null or movement.movementDate <= :dateTo)
            order by movement.postedAt desc, movement.id desc
            """,
        countQuery = """
            select count(movement)
            from StockMovement movement
            where movement.isDeleted = false
              and movement.tenantId = :tenantId
              and movement.companyId = :companyId
              and movement.warehouseId = :warehouseId
              and movement.status in :statuses
              and (:dateFrom is null or movement.movementDate >= :dateFrom)
              and (:dateTo is null or movement.movementDate <= :dateTo)
            """
    )
    Page<Warehouse360MovementProjection> findWarehouse360Movements(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseId") UUID warehouseId,
        @Param("statuses") List<String> statuses,
        @Param("dateFrom") LocalDate dateFrom,
        @Param("dateTo") LocalDate dateTo,
        Pageable pageable
    );

    @Query("""
        select m from StockMovement m
        where m.isDeleted = false
          and m.tenantId = :tenantId
          and m.materialId = :materialId
          and (:warehouseIdsEmpty = true or m.warehouseId in :warehouseIds)
          and m.status in :statuses
        order by m.postedAt asc
        """)
    List<StockMovement> findMovementsForValuationAsc(
        @Param("tenantId") String tenantId,
        @Param("materialId") UUID materialId,
        @Param("warehouseIdsEmpty") boolean warehouseIdsEmpty,
        @Param("warehouseIds") List<UUID> warehouseIds,
        @Param("statuses") List<String> statuses
    );

}
