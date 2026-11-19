package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.PurchaseOrderItem;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

public interface PurchaseOrderItemRepository extends JpaRepository<PurchaseOrderItem, UUID>, JpaSpecificationExecutor<PurchaseOrderItem> {
    Optional<PurchaseOrderItem> findAnyById(UUID id);
    Optional<PurchaseOrderItem> findByIdAndIsDeletedFalse(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from PurchaseOrderItem e where e.id = :id and e.isDeleted = false")
    Optional<PurchaseOrderItem> findByIdForUpdate(@Param("id") UUID id);

    @EntityGraph(attributePaths = {"material", "material.materialGroup", "material.unitOfMeasure"})
    List<PurchaseOrderItem> findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(String tenantId, UUID purchaseOrderId);

    List<PurchaseOrderItem> findByTenantIdAndCompanyIdAndPurchaseOrderIdInAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        Collection<UUID> purchaseOrderIds
    );

    @Query("""
        select item
        from PurchaseOrderItem item
        join fetch item.purchaseOrder purchaseOrder
        left join fetch purchaseOrder.supplier supplier
        left join fetch item.material material
        left join fetch material.unitOfMeasure unitOfMeasure
        where item.tenantId = :tenantId
          and item.companyId = :companyId
          and item.id in :ids
          and item.isDeleted = false
          and purchaseOrder.tenantId = :tenantId
          and purchaseOrder.companyId = :companyId
          and purchaseOrder.isDeleted = false
        """)
    List<PurchaseOrderItem> findWorkspaceLineageItems(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("ids") List<UUID> ids
    );

    @Query("""
        select i
        from PurchaseOrderItem i
        join fetch i.purchaseOrder p
        left join fetch i.material m
        where i.tenantId = :tenantId
          and i.companyId = :companyId
          and i.isDeleted = false
          and p.tenantId = :tenantId
          and p.companyId = :companyId
          and p.isDeleted = false
          and i.lineType = com.enterprise.mm.enums.ProcurementLineType.MATERIAL
          and coalesce(i.receivedQuantity, 0) > 0
          and (cast(:asOfDate as date) is null or p.orderDate is null or p.orderDate <= :asOfDate)
        """)
    List<PurchaseOrderItem> findManagementOpportunityReceiptCandidates(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("asOfDate") LocalDate asOfDate
    );

    @Query("""
        SELECT e
        FROM PurchaseOrderItem e
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
            AND (:purchaseOrderId IS NULL OR e.purchaseOrderId = :purchaseOrderId)
            AND (:materialIdsEmpty = true OR e.materialId IN :materialIds)
        """)
    Page<PurchaseOrderItem> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("purchaseOrderId") UUID purchaseOrderId,
        @Param("materialIds") List<UUID> materialIds,
        @Param("materialIdsEmpty") boolean materialIdsEmpty,
        Pageable pageable
    );
}
