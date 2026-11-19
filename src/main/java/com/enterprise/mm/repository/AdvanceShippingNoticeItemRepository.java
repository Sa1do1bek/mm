package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.AdvanceShippingNoticeItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdvanceShippingNoticeItemRepository extends JpaRepository<AdvanceShippingNoticeItem, UUID> {
    Optional<AdvanceShippingNoticeItem> findAnyById(UUID id);
    Optional<AdvanceShippingNoticeItem> findByIdAndIsDeletedFalse(UUID id);
    List<AdvanceShippingNoticeItem> findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(String tenantId, UUID companyId, UUID asnId);

    List<AdvanceShippingNoticeItem> findByTenantIdAndCompanyIdAndSupplierPoCommitmentIdAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID supplierPoCommitmentId
    );

    /** Real submitted quantity query for ASN-remaining-quantity validation: sums item
     * quantity across submitted/non-cancelled ASN documents referencing the commitment,
     * excluding one ASN (the draft currently being edited) so an update does not
     * double-count itself against its own remaining-quantity check. */
    @Query("""
        select coalesce(sum(item.quantity), 0)
        from AdvanceShippingNoticeItem item
        join item.asn asn
        where item.tenantId = :tenantId
          and item.companyId = :companyId
          and item.supplierPoCommitmentId = :commitmentId
          and item.isDeleted = false
          and asn.isDeleted = false
          and asn.status <> 'DRAFT'
          and asn.status <> 'CANCELLED'
          and (:excludeAsnId is null or asn.id <> :excludeAsnId)
        """)
    java.math.BigDecimal sumActiveQuantityForCommitment(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("commitmentId") UUID commitmentId,
        @Param("excludeAsnId") UUID excludeAsnId
    );

    @Query("""
        SELECT e
        FROM AdvanceShippingNoticeItem e
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
    Page<AdvanceShippingNoticeItem> search(
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
