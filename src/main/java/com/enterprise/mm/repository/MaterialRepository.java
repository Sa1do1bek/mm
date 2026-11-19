package com.enterprise.mm.repository;

import com.enterprise.mm.entity.Material;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.MaterialSubtype;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.payload.MaterialDashboardProjection;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface MaterialRepository extends JpaRepository<Material, UUID> {
    @EntityGraph(attributePaths = "unitOfMeasure")
    @Query("""
        select m from Material m
        where m.tenantId = :tenantId and m.companyId = :companyId
          and m.isDeleted = false and m.status = 'ACTIVE'
          and m.materialType = com.enterprise.mm.enums.MaterialType.FIXED_ASSET
          and (:search = '' or locate(:search, lower(m.code)) > 0 or locate(:search, lower(m.name)) > 0)
          and (:code = '' or locate(:code, lower(m.code)) > 0)
          and (:name = '' or locate(:name, lower(m.name)) > 0)
        """)
    Page<Material> findFixedAssetStockMaterials(@Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId, @Param("search") String search,
        @Param("code") String code, @Param("name") String name, Pageable pageable);

    @Query("""
        select m from Material m
        where m.tenantId = :tenantId and m.companyId = :companyId
          and m.isDeleted = false and m.status = 'ACTIVE'
          and ((m.materialGroupId = :groupId and m.unitOfMeasureId = :unitId
                and not exists (select b.id from ContractMaterialLink b where b.tenantId = :tenantId
                    and b.companyId = :companyId and b.specificationId = :specificationId and b.materialId = m.id))
              or m.id in :verifiedMaterialIds)
          and m.materialType = com.enterprise.mm.enums.MaterialType.TMB
          and (:allowSerialized = true or m.serialTrackingPolicy <> com.enterprise.mm.enums.SerialTrackingPolicy.REQUIRED)
          and (:search = '' or lower(m.name) like concat('%', :search, '%') or lower(m.code) like concat('%', :search, '%'))
        """)
    Page<Material> findContractGroupReceiptMembers(@Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId, @Param("groupId") UUID groupId, @Param("unitId") UUID unitId,
        @Param("specificationId") UUID specificationId, @Param("verifiedMaterialIds") List<UUID> verifiedMaterialIds,
        @Param("allowSerialized") boolean allowSerialized, @Param("search") String search, Pageable pageable);

    @Query("""
        select m from Material m
        where m.tenantId = :tenantId and m.companyId = :companyId
          and m.isDeleted = false and m.status = 'ACTIVE'
          and (exists (select b.id from StockBalance b where b.materialId = m.id
              and b.tenantId = :tenantId and b.companyId = :companyId
              and b.warehouseId = :warehouseId and b.isDeleted = false)
            or exists (select v.id from StockMovement v where v.materialId = m.id
              and v.tenantId = :tenantId and v.companyId = :companyId
              and v.warehouseId = :warehouseId and v.isDeleted = false))
        """)
    List<Material> findWarehouseRiskMaterials(@Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId, @Param("warehouseId") UUID warehouseId);

    Optional<Material> findAnyById(UUID id);

    @EntityGraph(attributePaths = {"materialGroup"})
    @Query("""
        select material from Material material
        where material.tenantId = :tenantId and material.companyId = :companyId
          and material.isDeleted = false
          and (:missingOnly = false or material.mxikCode is null or trim(material.mxikCode) = '')
        """)
    Page<Material> findForMxikReconciliation(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("missingOnly") boolean missingOnly,
        Pageable pageable
    );

    @EntityGraph(attributePaths = {"materialGroup"})
    @Query("""
        select material from Material material
        where material.tenantId = :tenantId and material.companyId = :companyId
          and material.isDeleted = false
          and (:missingOnly = false or material.mxikCode is null or trim(material.mxikCode) = '')
          and (lower(material.name) like concat('%', :search, '%')
               or lower(material.code) like concat('%', :search, '%'))
        """)
    Page<Material> searchForMxikReconciliation(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("missingOnly") boolean missingOnly,
        @Param("search") String search,
        Pageable pageable
    );

    @EntityGraph(attributePaths = {"materialGroup"})
    @Query("""
        select material from Material material
        where material.tenantId = :tenantId and material.companyId = :companyId
          and material.isDeleted = false and material.id <> :materialId
          and material.materialGroupId = :groupId
          and material.mxikCode is not null and trim(material.mxikCode) <> ''
        order by material.name asc
        """)
    List<Material> findMxikPeers(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialId") UUID materialId,
        @Param("groupId") UUID groupId,
        Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select material from Material material
        where material.tenantId = :tenantId and material.companyId = :companyId
          and material.id = :materialId and material.isDeleted = false
        """)
    Optional<Material> lockForMxikReconciliation(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialId") UUID materialId
    );

    Optional<Material> findByIdAndIsDeletedFalse(UUID id);
    List<Material> findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(String tenantId, UUID companyId, java.util.Collection<UUID> ids);

    Optional<Material> findByTenantIdAndIdAndIsDeletedFalse(String tenantId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""

            select material
        from Material material
        where material.tenantId = :tenantId
          and material.companyId = :companyId
          and material.id = :materialId
          and material.isDeleted = false
        """)
    Optional<Material> lockForStockBalanceCreation(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialId") UUID materialId
    );

    @EntityGraph(attributePaths = {"mxik", "materialGroup", "unitOfMeasure", "replacementMaterial", "manufacturerRef"})
    @Query("""
        select material
        from Material material
        where material.tenantId = :tenantId
          and material.id = :materialId
          and material.isDeleted = false
        """)
    Optional<Material> findMaterial360ByTenantAndId(
        @Param("tenantId") String tenantId,
        @Param("materialId") UUID materialId
    );

    Optional<Material> findByTenantIdAndCodeAndIsDeletedFalse(String tenantId, String code);

    Optional<Material> findByTenantIdAndCompanyIdAndCodeAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        String code
    );

    boolean existsByTenantIdAndCompanyIdAndCodeAndIsDeletedFalse(String tenantId, UUID companyId, String code);

    boolean existsByTenantIdAndCompanyIdAndCodeAndIdNotAndIsDeletedFalse(String tenantId, UUID companyId, String code, UUID id);

    @Query(value = """
        SELECT EXISTS (
            SELECT 1 FROM materials m
            WHERE m.tenant_id = :tenantId AND m.company_id = :companyId
              AND m.is_delete = false AND upper(btrim(m.code)) = upper(btrim(:code))
              AND (:excludedId IS NULL OR m.id <> :excludedId)
        )
        """, nativeQuery = true)
    boolean existsActiveNormalizedCode(@Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId, @Param("code") String code,
        @Param("excludedId") UUID excludedId);

    boolean existsByTenantIdAndCompanyIdAndBarcodeAndIsDeletedFalse(String tenantId, UUID companyId, String barcode);

    boolean existsByTenantIdAndCompanyIdAndBarcodeAndIdNotAndIsDeletedFalse(String tenantId, UUID companyId, String barcode, UUID id);

    boolean existsByTenantIdAndCompanyIdAndQrCodeAndIsDeletedFalse(String tenantId, UUID companyId, String qrCode);

    boolean existsByTenantIdAndCompanyIdAndQrCodeAndIdNotAndIsDeletedFalse(String tenantId, UUID companyId, String qrCode, UUID id);

    boolean existsByTenantIdAndCompanyIdAndRfidTagAndIsDeletedFalse(String tenantId, UUID companyId, String rfidTag);

    boolean existsByTenantIdAndCompanyIdAndRfidTagAndIdNotAndIsDeletedFalse(String tenantId, UUID companyId, String rfidTag, UUID id);

    Optional<Material> findFirstByTenantIdAndBarcodeAndIsDeletedFalse(String tenantId, String barcode);

    Optional<Material> findFirstByTenantIdAndQrCodeAndIsDeletedFalse(String tenantId, String qrCode);

    Optional<Material> findFirstByTenantIdAndRfidTagAndIsDeletedFalse(String tenantId, String rfidTag);

    List<Material> findByTenantIdAndStatusAndIsDeletedFalse(String tenantId, String status);

    List<Material> findByTenantIdAndCompanyIdAndStatusAndIsDeletedFalse(String tenantId, UUID companyId, String status);

    List<Material> findByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId);

    /**
     * Includes soft-deleted rows so a full master-data snapshot can deactivate stale projections.
     */
    List<Material> findByTenantIdAndCompanyIdOrderByIdAsc(String tenantId, UUID companyId);

    long countByTenantIdAndStatusAndIsDeletedFalse(String tenantId, String status);

    long countByTenantIdAndCompanyIdAndStatusAndIsDeletedFalse(String tenantId, UUID companyId, String status);

    @Query("""
        select count(material)
        from Material material
        left join material.mxik mx
        where material.tenantId = :tenantId
          and material.companyId = :companyId
          and material.status = :status
          and material.isDeleted = false
          and (
            mx.type = :mxikType
            or (:includeNullMxik = true and mx.id is null)
          )
        """)
    long countVisibleCatalogMaterials(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("status") String status,
        @Param("mxikType") String mxikType,
        @Param("includeNullMxik") boolean includeNullMxik
    );

    @Query(
        value = """
            select distinct m
            from Material m
            join StockBalance b on b.materialId = m.id
            where m.isDeleted = false
              and b.isDeleted = false
              and m.tenantId = :tenantId
              and m.companyId = :companyId
              and b.tenantId = :tenantId
              and b.companyId = :companyId
              and b.createdAt between :fromCreatedAt and :toCreatedAt
            """,
        countQuery = """
            select count(distinct m.id)
            from Material m
            join StockBalance b on b.materialId = m.id
            where m.isDeleted = false
              and b.isDeleted = false
              and m.tenantId = :tenantId
              and m.companyId = :companyId
              and b.tenantId = :tenantId
              and b.companyId = :companyId
              and b.createdAt between :fromCreatedAt and :toCreatedAt
            """
    )
    org.springframework.data.domain.Page<Material> findByTenantIdAndCompanyIdAndStockBalanceCreatedAtBetween(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("fromCreatedAt") LocalDateTime fromCreatedAt,
        @Param("toCreatedAt") LocalDateTime toCreatedAt,
        org.springframework.data.domain.Pageable pageable
    );

    @Query("""
        select distinct material.tenantId from Material material
        where material.isDeleted = false
          and material.status = :status
        """)
    List<String> findDistinctTenantIdsByStatus(@Param("status") String status);

    @Query("""
    SELECT e
    FROM Material e
    LEFT JOIN e.mxik mx
    WHERE
        e.tenantId = :tenantId
        AND (:companyId IS NULL OR e.companyId = :companyId)
        AND (:includeDeleted = true OR e.isDeleted = false)
        AND (:search IS NULL OR
            LOWER(COALESCE(e.code, '')) LIKE CONCAT('%', LOWER(CAST(:search AS string)), '%')
            OR LOWER(COALESCE(e.name, '')) LIKE CONCAT('%', LOWER(CAST(:search AS string)), '%')
            OR LOWER(COALESCE(e.mxikCode, '')) LIKE CONCAT('%', LOWER(CAST(:search AS string)), '%'))
        AND (CAST(:dateFrom AS timestamp) IS NULL OR e.createdAt >= :dateFrom)
        AND (CAST(:dateTo AS timestamp) IS NULL OR e.createdAt <= :dateTo)
        AND (:statusEmpty = true OR e.status IN :status )
        AND (:id IS NULL OR e.id = :id)
        AND (:code IS NULL OR LOWER(COALESCE(e.code, '')) LIKE CONCAT('%', LOWER(CAST(:code AS string)), '%'))
        AND (:name IS NULL OR LOWER(COALESCE(e.name, '')) LIKE CONCAT('%', LOWER(CAST(:name AS string)), '%'))
        AND (:description IS NULL OR LOWER(COALESCE(e.description, '')) LIKE CONCAT('%', LOWER(CAST(:description AS string)), '%'))
        AND (:deleted IS NULL OR e.isDeleted = :deleted)
        AND (:createdBy IS NULL OR LOWER(COALESCE(e.createdBy, '')) LIKE CONCAT('%', LOWER(CAST(:createdBy AS string)), '%'))
        AND (:updatedBy IS NULL OR LOWER(COALESCE(e.updatedBy, '')) LIKE CONCAT('%', LOWER(CAST(:updatedBy AS string)), '%'))
        AND (:version IS NULL OR e.version = :version)
        AND (:materialGroupId IS NULL OR e.materialGroupId = :materialGroupId)
        AND (:unitOfMeasureId IS NULL OR e.unitOfMeasureId = :unitOfMeasureId)
        AND (:valuationMethod IS NULL OR e.valuationMethod = :valuationMethod)
        AND (:minStock IS NULL OR e.minStock = :minStock)
        AND (:safetyStock IS NULL OR e.safetyStock = :safetyStock)
        AND (:reorderPoint IS NULL OR e.reorderPoint = :reorderPoint)
        AND (:barcode IS NULL OR LOWER(COALESCE(e.barcode, '')) LIKE CONCAT('%', LOWER(CAST(:barcode AS string)), '%'))
        AND (:qrCode IS NULL OR LOWER(COALESCE(e.qrCode, '')) LIKE CONCAT('%', LOWER(CAST(:qrCode AS string)), '%'))
        AND (:rfidTag IS NULL OR LOWER(COALESCE(e.rfidTag, '')) LIKE CONCAT('%', LOWER(CAST(:rfidTag AS string)), '%'))
        AND (:mxikCode IS NULL OR LOWER(COALESCE(e.mxikCode, '')) LIKE CONCAT('%', LOWER(CAST(:mxikCode AS string)), '%'))
        AND (:tnvedCodeId IS NULL OR e.tnvedCodeId = :tnvedCodeId)
        AND (:tnvedCode IS NULL OR LOWER(COALESCE(e.tnvedCode, '')) LIKE CONCAT('%', LOWER(CAST(:tnvedCode AS string)), '%'))
        AND (:article IS NULL OR LOWER(COALESCE(e.article, '')) LIKE CONCAT('%', LOWER(CAST(:article AS string)), '%'))
        AND (:manufacturer IS NULL OR LOWER(COALESCE(e.manufacturer, '')) LIKE CONCAT('%', LOWER(CAST(:manufacturer AS string)), '%'))
        AND (:countryOfOrigin IS NULL OR LOWER(COALESCE(e.countryOfOrigin, '')) LIKE CONCAT('%', LOWER(CAST(:countryOfOrigin AS string)), '%'))
        AND (:shelfLifeDays IS NULL OR e.shelfLifeDays = :shelfLifeDays)
        AND (:hazardClass IS NULL OR e.hazardClass = :hazardClass)
        AND (:maxStock IS NULL OR e.maxStock = :maxStock)
        AND (:replacementMaterialId IS NULL OR e.replacementMaterialId = :replacementMaterialId)
        AND (:lifecycleReason IS NULL OR LOWER(COALESCE(e.lifecycleReason, '')) LIKE CONCAT('%', LOWER(CAST(:lifecycleReason AS string)), '%'))
        AND (CAST(:lifecycleChangedAt AS timestamp) IS NULL OR e.lifecycleChangedAt = :lifecycleChangedAt )
        AND (:materialType IS NULL OR e.materialType = :materialType)
        AND (:materialSubtype IS NULL OR e.materialSubtype = :materialSubtype
            OR (:materialSubtype = com.enterprise.mm.enums.MaterialSubtype.MATERIAL
                AND e.materialType = com.enterprise.mm.enums.MaterialType.TMB AND e.materialSubtype IS NULL))
        AND (:mxikType IS NULL OR mx.type = :mxikType OR (:includeNullMxik = true AND mx.id IS NULL) )
        AND (:stockStatusEmpty = true OR (
                  'OUT_OF_STOCK' IN :stockStatus
                  AND NOT EXISTS (
                      SELECT 1
                      FROM StockBalance sb2
                      WHERE sb2.materialId = e.id
                        AND sb2.tenantId = e.tenantId
                        AND (:companyId IS NULL OR sb2.companyId = :companyId)
                        AND sb2.availableQuantity > 0 ) )
                    OR ('LOW_STOCK' IN :stockStatus
                  AND EXISTS (
                      SELECT 1
                      FROM StockBalance sb3
                      WHERE sb3.materialId = e.id
                        AND sb3.tenantId = e.tenantId
                        AND (:companyId IS NULL OR sb3.companyId = :companyId)
                        AND sb3.availableQuantity > 0
                        AND sb3.availableQuantity <= e.minStock
                  )
              )
          )
    """)
    Page<Material> search(
            @Param("tenantId") String tenantId,
            @Param("companyId") UUID companyId,
            @Param("includeDeleted") boolean includeDeleted,
            @Param("search") String search,
            @Param("dateFrom") LocalDateTime dateFrom,
            @Param("dateTo") LocalDateTime dateTo,
            @Param("status") List<String> status,
            @Param("statusEmpty") boolean statusEmpty,
            @Param("id") UUID id,
            @Param("code") String code,
            @Param("name") String name,
            @Param("description") String description,
            @Param("deleted") Boolean deleted,
            @Param("createdBy") String createdBy,
            @Param("updatedBy") String updatedBy,
            @Param("version") Long version,
            @Param("materialGroupId") UUID materialGroupId,
            @Param("unitOfMeasureId") UUID unitOfMeasureId,
            @Param("valuationMethod") ValuationMethod valuationMethod,
            @Param("minStock") BigDecimal minStock,
            @Param("safetyStock") BigDecimal safetyStock,
            @Param("reorderPoint") BigDecimal reorderPoint,
            @Param("barcode") String barcode,
            @Param("qrCode") String qrCode,
            @Param("rfidTag") String rfidTag,
            @Param("mxikCode") String mxikCode,
            @Param("tnvedCodeId") UUID tnvedCodeId,
            @Param("tnvedCode") String tnvedCode,
            @Param("article") String article,
            @Param("manufacturer") String manufacturer,
            @Param("countryOfOrigin") String countryOfOrigin,
            @Param("shelfLifeDays") Integer shelfLifeDays,
            @Param("hazardClass") String hazardClass,
            @Param("maxStock") BigDecimal maxStock,
            @Param("replacementMaterialId") UUID replacementMaterialId,
            @Param("lifecycleReason") String lifecycleReason,
            @Param("lifecycleChangedAt") LocalDateTime lifecycleChangedAt,
            @Param("materialType") MaterialType materialType,
            @Param("materialSubtype") MaterialSubtype materialSubtype,
            @Param("mxikType") String mxikType,
            @Param("includeNullMxik") boolean includeNullMxik,
            @Param("stockStatus") List<String> stockStatus,
            @Param("stockStatusEmpty") boolean stockStatusEmpty,
            Pageable pageable
    );
    @Query(value = """
    WITH stock AS (
        SELECT
            sb.material_id,
            SUM(sb.available_quantity) AS available_quantity,
            SUM(sb.total_value) AS total_value
        FROM stock_balances sb
        WHERE sb.tenant_id = :tenantId
          AND sb.company_id = :companyId
        GROUP BY sb.material_id
    )
    SELECT
        COUNT(m.id) AS totalMaterials,

        COUNT(*) FILTER (
            WHERE COALESCE(s.available_quantity, 0) > 0
              AND COALESCE(s.available_quantity, 0) <= COALESCE(m.reorder_point, 0)
        ) AS lowStock,

        COUNT(*) FILTER (
            WHERE COALESCE(s.available_quantity, 0) = 0
        ) AS outOfStock,

        COALESCE(SUM(s.total_value), 0) AS value

    FROM materials m
    LEFT JOIN stock s
        ON s.material_id = m.id
    WHERE m.tenant_id = :tenantId
      AND m.company_id = :companyId
    """,
            nativeQuery = true)
    MaterialDashboardProjection getDashboard(
            @Param("tenantId") String tenantId,
            @Param("companyId") UUID companyId);

}
