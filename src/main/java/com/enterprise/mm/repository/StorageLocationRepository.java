package com.enterprise.mm.repository;

import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.enums.StorageZoneType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StorageLocationRepository extends JpaRepository<StorageLocation, UUID> {
    Optional<StorageLocation> findAnyById(UUID id);
    Optional<StorageLocation> findByIdAndIsDeletedFalse(UUID id);
    Optional<StorageLocation> findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID id);
    List<StorageLocation> findByTenantIdAndWarehouse_IdAndIsDeletedFalse(String tenantId, UUID warehouseId);
    List<StorageLocation> findByTenantIdAndCompanyIdAndWarehouseIdAndIsDeletedFalseOrderByCreatedAtAsc(String tenantId, UUID companyId, UUID warehouseId);
    Optional<StorageLocation> findByTenantIdAndCodeAndIsDeletedFalse(String tenantId, String code);
    Optional<StorageLocation> findFirstByTenantIdAndBarcodeAndIsDeletedFalse(String tenantId, String barcode);
    Optional<StorageLocation> findFirstByTenantIdAndQrCodeAndIsDeletedFalse(String tenantId, String qrCode);
    Optional<StorageLocation> findFirstByTenantIdAndRfidTagAndIsDeletedFalse(String tenantId, String rfidTag);

    @Query("""
        SELECT e
        FROM StorageLocation e
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
            AND (:id IS NULL OR e.id = :id)
            AND (:code IS NULL OR LOWER(COALESCE(e.code, '')) LIKE CONCAT('%', LOWER(CAST(:code AS string)), '%'))
            AND (:name IS NULL OR LOWER(COALESCE(e.name, '')) LIKE CONCAT('%', LOWER(CAST(:name AS string)), '%'))
            AND (:description IS NULL OR LOWER(COALESCE(e.description, '')) LIKE CONCAT('%', LOWER(CAST(:description AS string)), '%'))
            AND (:deleted IS NULL OR e.isDeleted = :deleted)
            AND (:createdBy IS NULL OR LOWER(COALESCE(e.createdBy, '')) LIKE CONCAT('%', LOWER(CAST(:createdBy AS string)), '%'))
            AND (:updatedBy IS NULL OR LOWER(COALESCE(e.updatedBy, '')) LIKE CONCAT('%', LOWER(CAST(:updatedBy AS string)), '%'))
            AND (:version IS NULL OR e.version = :version)
            AND (:warehouseId IS NULL OR e.warehouseId = :warehouseId)
            AND (:warehouseIdsEmpty = true OR e.warehouseId IN :warehouseIds)
            AND (:storageZoneType IS NULL OR e.storageZoneType = :storageZoneType)
            AND (:zoneCode IS NULL OR LOWER(COALESCE(e.zoneCode, '')) LIKE CONCAT('%', LOWER(CAST(:zoneCode AS string)), '%'))
            AND (:binCode IS NULL OR LOWER(COALESCE(e.binCode, '')) LIKE CONCAT('%', LOWER(CAST(:binCode AS string)), '%'))
            AND (:barcode IS NULL OR LOWER(COALESCE(e.barcode, '')) LIKE CONCAT('%', LOWER(CAST(:barcode AS string)), '%'))
            AND (:qrCode IS NULL OR LOWER(COALESCE(e.qrCode, '')) LIKE CONCAT('%', LOWER(CAST(:qrCode AS string)), '%'))
            AND (:rfidTag IS NULL OR LOWER(COALESCE(e.rfidTag, '')) LIKE CONCAT('%', LOWER(CAST(:rfidTag AS string)), '%'))
            AND (:temperatureZone IS NULL OR LOWER(COALESCE(e.temperatureZone, '')) LIKE CONCAT('%', LOWER(CAST(:temperatureZone AS string)), '%'))
            AND (:hazardClassAllowed IS NULL OR LOWER(COALESCE(e.hazardClassAllowed, '')) LIKE CONCAT('%', LOWER(CAST(:hazardClassAllowed AS string)), '%'))
            AND (:capacityQuantity IS NULL OR e.capacityQuantity = :capacityQuantity)
            AND (:maxWeight IS NULL OR e.maxWeight = :maxWeight)
            AND (:allowMixedMaterials IS NULL OR e.allowMixedMaterials = :allowMixedMaterials)
        """)
    Page<StorageLocation> search(
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
        @Param("warehouseId") UUID warehouseId,
        @Param("warehouseIds") java.util.List<UUID> warehouseIds,
        @Param("warehouseIdsEmpty") boolean warehouseIdsEmpty,
        @Param("storageZoneType") StorageZoneType storageZoneType,
        @Param("zoneCode") String zoneCode,
        @Param("binCode") String binCode,
        @Param("barcode") String barcode,
        @Param("qrCode") String qrCode,
        @Param("rfidTag") String rfidTag,
        @Param("temperatureZone") String temperatureZone,
        @Param("hazardClassAllowed") String hazardClassAllowed,
        @Param("capacityQuantity") java.math.BigDecimal capacityQuantity,
        @Param("maxWeight") java.math.BigDecimal maxWeight,
        @Param("allowMixedMaterials") Boolean allowMixedMaterials,
        Pageable pageable
    );

    @Query(
        value = """
            select location
            from StorageLocation location
            where location.isDeleted = false
              and location.tenantId = :tenantId
              and location.companyId = :companyId
              and location.warehouseId = :warehouseId
            order by location.code asc, location.id asc
            """,
        countQuery = """
            select count(location)
            from StorageLocation location
            where location.isDeleted = false
              and location.tenantId = :tenantId
              and location.companyId = :companyId
              and location.warehouseId = :warehouseId
            """
    )
    Page<StorageLocation> findWarehouse360Locations(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseId") UUID warehouseId,
        Pageable pageable
    );

    @Query("""
        select count(location)
        from StorageLocation location
        where location.isDeleted = false
          and location.tenantId = :tenantId
          and location.companyId = :companyId
          and location.warehouseId = :warehouseId
        """)
    long countWarehouse360Locations(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseId") UUID warehouseId
    );

}
