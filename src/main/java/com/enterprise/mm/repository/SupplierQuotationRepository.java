package com.enterprise.mm.repository;

import com.enterprise.mm.entity.SupplierQuotation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface SupplierQuotationRepository extends JpaRepository<SupplierQuotation, UUID>, JpaSpecificationExecutor<SupplierQuotation> {
    Optional<SupplierQuotation> findAnyById(UUID id);
    Optional<SupplierQuotation> findByIdAndIsDeletedFalse(UUID id);
    Optional<SupplierQuotation> findByTenantIdAndIdAndIsDeletedFalse(String tenantId, UUID id);

    Optional<SupplierQuotation> findByTenantIdAndCompanyIdAndRfq_IdAndSupplier_IdAndIsDeletedFalse(String tenantId, UUID companyId, UUID rfqId, UUID supplierId);

    List<SupplierQuotation> findByTenantIdAndRfq_IdAndIsDeletedFalseOrderByTotalAmountAsc(String tenantId, UUID rfqId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select quotation
        from SupplierQuotation quotation
        where quotation.tenantId = :tenantId
          and quotation.companyId = :companyId
          and quotation.id = :id
          and quotation.isDeleted = false
        """)
    Optional<SupplierQuotation> lockScopedById(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("id") UUID id
    );

    Optional<SupplierQuotation> findByTenantIdAndCompanyIdAndThreadIdAndCurrentRevisionTrueAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID threadId
    );

    List<SupplierQuotation> findByTenantIdAndCompanyIdAndThreadIdAndIsDeletedFalseOrderByRevisionNumberAsc(
        String tenantId,
        UUID companyId,
        UUID threadId
    );

    List<SupplierQuotation> findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderBySupplierIdAscRevisionNumberAsc(
        String tenantId,
        UUID companyId,
        UUID rfqId
    );

    List<SupplierQuotation> findByTenantIdAndCompanyIdAndRfqIdAndSupplierIdAndIsDeletedFalseOrderByRevisionNumberAsc(
        String tenantId,
        UUID companyId,
        UUID rfqId,
        UUID supplierId
    );

    List<SupplierQuotation> findByTenantIdAndCompanyIdAndRfqIdOrderBySupplierIdAscRevisionNumberAsc(
        String tenantId,
        UUID companyId,
        UUID rfqId
    );

    @Query("""
        select quotation
        from SupplierQuotation quotation
        where quotation.tenantId = :tenantId
          and quotation.companyId = :companyId
          and quotation.rfqId = :rfqId
          and quotation.isDeleted = false
          and quotation.status in :statuses
          and (quotation.threadId is null or quotation.currentRevision = true)
        order by quotation.totalAmount, quotation.id
        """)
    List<SupplierQuotation> findCurrentSubmittedByRfq(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("rfqId") UUID rfqId,
        @Param("statuses") List<String> statuses
    );

    @Query("""
        select quotation
        from SupplierQuotation quotation
        where quotation.tenantId = :tenantId
          and quotation.companyId = :companyId
          and quotation.rfqId = :rfqId
          and quotation.supplierId = :supplierId
          and quotation.isDeleted = false
          and quotation.status in :statuses
          and (quotation.threadId is null or quotation.currentRevision = true)
        order by quotation.revisionNumber desc nulls last, quotation.createdAt desc, quotation.id desc
        """)
    List<SupplierQuotation> findCurrentSubmittedByRfqAndSupplier(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("rfqId") UUID rfqId,
        @Param("supplierId") UUID supplierId,
        @Param("statuses") List<String> statuses
    );

    boolean existsByTenantIdAndCompanyIdAndRfqIdAndSelectedTrueAndIsDeletedFalse(String tenantId, UUID companyId, UUID rfqId);

    @Query("""
        SELECT e
        FROM SupplierQuotation e
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
            AND (:supplierId IS NULL OR e.supplierId = :supplierId)
        """)
    Page<SupplierQuotation> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("searchPattern") String searchPattern,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("supplierId") UUID supplierId,
        Pageable pageable
    );
}
