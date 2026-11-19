package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.InventoryItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InventoryItem i where i.id=:id and i.inventoryDocumentId=:document and i.tenantId=:tenant and i.companyId=:company and i.isDeleted=false")
    Optional<InventoryItem> findScopedForUpdate(@Param("id") UUID id, @Param("document") UUID document, @Param("tenant") String tenant, @Param("company") UUID company);
    Optional<InventoryItem> findByIdAndInventoryDocumentIdAndTenantIdAndCompanyIdAndIsDeletedFalse(UUID id, UUID documentId, String tenantId, UUID companyId);
    Optional<InventoryItem> findAnyById(UUID id);
    Optional<InventoryItem> findByIdAndIsDeletedFalse(UUID id);
    List<InventoryItem> findByTenantIdAndInventoryDocument_IdAndIsDeletedFalse(String tenantId, UUID inventoryDocumentId);

    List<InventoryItem> findByTenantIdAndInventoryDocumentIdAndIsDeletedFalse(String tenantId, UUID inventoryDocumentId);

    boolean existsByTenantIdAndInventoryDocumentIdAndMaterialIdAndIsDeletedFalse(String tenantId, UUID inventoryDocumentId, UUID materialId);

    @Query("select distinct i.materialId from InventoryItem i where i.tenantId=:tenant and i.inventoryDocumentId=:document and i.isDeleted=false")
    List<UUID> findSnapshotMaterialIds(@Param("tenant") String tenant, @Param("document") UUID document);

    Optional<InventoryItem> findFirstByTenantIdAndInventoryDocumentIdAndMaterialIdAndIsDeletedFalse(String tenantId, UUID inventoryDocumentId, UUID materialId);

    @Query("""
        SELECT e
        FROM InventoryItem e
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
            AND (:materialIdsEmpty = true OR e.materialId IN :materialIds)
            AND (:inventoryDocumentId IS NULL OR e.inventoryDocumentId = :inventoryDocumentId)
            AND (:warehouseId IS NULL OR e.warehouseId = :warehouseId)
            AND (:warehouseIdsEmpty = true OR e.warehouseId IN :warehouseIds)
            AND (:readAll = true OR (:warehouseReader = true AND e.inventoryDocument.globalWarehouse = false) OR EXISTS (SELECT cm.id FROM CommissionMember cm
                WHERE cm.tenantId=e.tenantId AND cm.companyId=e.companyId AND cm.commissionId=e.inventoryDocument.commissionId
                AND cm.userId=:readerId AND cm.active=true AND cm.isDeleted=false))
        """)
    Page<InventoryItem> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("materialIds") List<UUID> materialIds,
        @Param("materialIdsEmpty") boolean materialIdsEmpty,
        @Param("inventoryDocumentId") UUID inventoryDocumentId,
        @Param("warehouseId") UUID warehouseId,
        @Param("warehouseIds") java.util.List<UUID> warehouseIds,
        @Param("warehouseIdsEmpty") boolean warehouseIdsEmpty,
        @Param("readAll") boolean readAll,
        @Param("warehouseReader") boolean warehouseReader,
        @Param("readerId") UUID readerId,
        Pageable pageable
    );

    long countByTenantIdAndInventoryDocumentIdAndIsDeletedFalse(String tenantId, UUID inventoryDocumentId);

    long countByTenantIdAndInventoryDocumentIdAndCountedQuantityIsNotNullAndIsDeletedFalse(String tenantId, UUID inventoryDocumentId);

    long countByTenantIdAndInventoryDocumentIdAndDifferenceQuantityNotAndIsDeletedFalse(String tenantId, UUID inventoryDocumentId, java.math.BigDecimal differenceQuantity);

    long countByTenantIdAndInventoryDocumentIdAndLineStatusInAndIsDeletedFalse(String tenantId, UUID inventoryDocumentId, List<com.enterprise.mm.enums.InventoryLineStatus> lineStatuses);
}
