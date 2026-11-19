package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.SupplierQuotationItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SupplierQuotationItemRepository extends JpaRepository<SupplierQuotationItem, UUID>, JpaSpecificationExecutor<SupplierQuotationItem> {
    Optional<SupplierQuotationItem> findAnyById(UUID id);
    Optional<SupplierQuotationItem> findByIdAndIsDeletedFalse(UUID id);
    List<SupplierQuotationItem> findByTenantIdAndQuotation_IdAndIsDeletedFalse(String tenantId, UUID quotationId);
    List<SupplierQuotationItem> findByTenantIdAndCompanyIdAndQuotationIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId,
        UUID companyId,
        UUID quotationId
    );
    List<SupplierQuotationItem> findByTenantIdAndCompanyIdAndQuotationIdInAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId,
        UUID companyId,
        List<UUID> quotationIds
    );
    boolean existsByQuotationIdAndRfqItemIdAndIsDeletedFalse(UUID quotationId, UUID rfqItemId);

    @Query("""
        SELECT e
        FROM SupplierQuotationItem e
        WHERE
            e.tenantId = :tenantId
            AND (:companyId IS NULL OR e.companyId = :companyId)
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (:searchPattern IS NULL OR
                 LOWER(COALESCE(e.code, '')) LIKE :searchPattern
                 OR LOWER(COALESCE(e.name, '')) LIKE :searchPattern)
            AND (CAST(:dateFrom AS timestamp) IS NULL OR e.createdAt >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR e.createdAt <= :dateTo)
            AND (:statusEmpty = true OR e.status IN :status)
            AND (:materialIdsEmpty = true OR e.materialId IN :materialIds)
        """)
    Page<SupplierQuotationItem> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("searchPattern") String searchPattern,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("materialIds") List<UUID> materialIds,
        @Param("materialIdsEmpty") boolean materialIdsEmpty,
        Pageable pageable
    );
}
