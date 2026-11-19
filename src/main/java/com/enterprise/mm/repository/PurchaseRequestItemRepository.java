package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.PurchaseRequestItem;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;

public interface PurchaseRequestItemRepository extends JpaRepository<PurchaseRequestItem, UUID>, JpaSpecificationExecutor<PurchaseRequestItem> {
    Optional<PurchaseRequestItem> findAnyById(UUID id);
    Optional<PurchaseRequestItem> findByIdAndIsDeletedFalse(UUID id);
    List<PurchaseRequestItem> findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(String tenantId, UUID purchaseRequestId);

    List<PurchaseRequestItem> findByTenantIdAndCompanyIdAndPurchaseRequestIdInAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        Collection<UUID> purchaseRequestIds
    );

    @Query("""
        select item
        from PurchaseRequestItem item
        join fetch item.purchaseRequest request
        where item.tenantId = :tenantId
          and item.companyId = :companyId
          and item.id in :ids
          and item.isDeleted = false
          and request.tenantId = :tenantId
          and request.companyId = :companyId
          and request.isDeleted = false
        """)
    List<PurchaseRequestItem> findWorkspaceLineageItems(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("ids") Collection<UUID> ids
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from PurchaseRequestItem i where i.id in :ids order by i.id")
    List<PurchaseRequestItem> findAllByIdInForUpdate(@Param("ids") Collection<UUID> ids);

    @Query(value = """
        select count(*)
        from purchase_request_items pri
        join purchase_requests pr on pr.id = pri.purchase_request_id
        join materials m on m.id = pri.material_id
        left join unit_of_measures uom on uom.id = m.unit_of_measure_id
        where pri.tenant_id = :tenantId
          and pr.tenant_id = :tenantId
          and m.tenant_id = :tenantId
          and pri.company_id = :companyId
          and pr.company_id = :companyId
          and m.company_id = :companyId
          and pri.purchase_request_id = :purchaseRequestId
          and pri.is_delete = false
          and pr.is_delete = false
          and m.is_delete = false
          and pr.request_type = 'GOODS'
          and pri.line_type = 'MATERIAL'
          and pri.quantity > 0
          and m.status = 'ACTIVE'
          and coalesce(nullif(trim(pri.unit), ''), nullif(trim(uom.code), ''), nullif(trim(uom.measurement_name_short), '')) is not null
          and not exists (
              select 1
              from rfq_purchase_request_sources src
              where src.tenant_id = pri.tenant_id
                and src.company_id = pri.company_id
                and src.purchase_request_item_id = pri.id
                and src.status = 'ACTIVE'
                and src.is_delete = false
          )
        """, nativeQuery = true)
    long countEligibleUnallocatedMaterialLines(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("purchaseRequestId") UUID purchaseRequestId);

    @Query("""
        select coalesce(sum(item.quantity), 0)
        from PurchaseRequestItem item, PurchaseRequest request
        where item.isDeleted = false
          and request.isDeleted = false
          and item.tenantId = :tenantId
          and request.tenantId = :tenantId
          and item.purchaseRequest.id = request.id
          and item.material.id = :materialId
          and request.status in :statuses
        """)
    BigDecimal sumOpenQuantityByMaterial(@Param("tenantId") String tenantId, @Param("materialId") UUID materialId, @Param("statuses") List<String> statuses);

    @Query("""
        SELECT e
        FROM PurchaseRequestItem e
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
        """)
    Page<PurchaseRequestItem> search(
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
        Pageable pageable
    );
}
