package com.enterprise.mm.repository;

import com.enterprise.mm.entity.AdvanceShippingNotice;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdvanceShippingNoticeRepository extends JpaRepository<AdvanceShippingNotice, UUID> {
    Optional<AdvanceShippingNotice> findAnyById(UUID id);
    Optional<AdvanceShippingNotice> findByIdAndIsDeletedFalse(UUID id);
    Optional<AdvanceShippingNotice> findByTenantIdAndCompanyIdAndAsnNumberAndIsDeletedFalse(String tenantId, UUID companyId, String asnNumber);

    List<AdvanceShippingNotice> findByTenantIdAndCompanyIdAndPurchaseOrder_IdAndIsDeletedFalse(String tenantId, UUID companyId, UUID purchaseOrderId);

    /** Portal-originated ASNs for a PO, used by the ASN cancellation hook and by the
     * "unresolved submitted ASN" close-blocking check. */
    List<AdvanceShippingNotice> findByTenantIdAndCompanyIdAndPurchaseOrderIdAndSourceAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID purchaseOrderId, String source
    );

    List<AdvanceShippingNotice> findByTenantIdAndCompanyIdAndPurchaseOrderIdAndStatusInAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID purchaseOrderId, List<String> statuses
    );

    @Query("""
        SELECT e
        FROM AdvanceShippingNotice e
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
            AND (:supplierId IS NULL OR e.supplierId = :supplierId)
            AND (:contractId IS NULL OR e.contractId = :contractId)
            AND (:purchaseOrderId IS NULL OR e.purchaseOrderId = :purchaseOrderId)
        """)
    Page<AdvanceShippingNotice> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("supplierId") UUID supplierId,
        @Param("contractId") UUID contractId,
        @Param("purchaseOrderId") UUID purchaseOrderId,
        Pageable pageable
    );
}
