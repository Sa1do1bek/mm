package com.enterprise.mm.repository;

import com.enterprise.mm.entity.RFQ;
import jakarta.persistence.LockModeType;
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

public interface RFQRepository extends JpaRepository<RFQ, UUID>, JpaSpecificationExecutor<RFQ> {
    Optional<RFQ> findAnyById(UUID id);
    Optional<RFQ> findByIdAndIsDeletedFalse(UUID id);
    Optional<RFQ> findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(String tenantId, UUID purchaseRequestId);
    List<RFQ> findByTenantIdAndPurchaseRequest_IdInAndIsDeletedFalse(String tenantId, List<UUID> purchaseRequestIds);

    List<RFQ> findByTenantIdAndPurchaseRequestIdAndIsDeletedFalse(String tenantId, UUID purchaseRequestId);

    List<RFQ> findByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId);
    List<RFQ> findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(String tenantId, UUID companyId, List<UUID> ids);

    List<RFQ> findByTenantIdAndIdInAndIsDeletedFalse(String tenantId, List<UUID> ids);

    Optional<RFQ> findByTenantIdAndCompanyIdAndProcurementProcedureIdAndPurposeAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID procurementProcedureId,
        String purpose
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RFQ r where r.id = :id")
    Optional<RFQ> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select rfq
        from RFQ rfq
        where rfq.tenantId = :tenantId
          and rfq.companyId = :companyId
          and rfq.id = :id
          and rfq.isDeleted = false
        """)
    Optional<RFQ> lockScopedById(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("id") UUID id
    );

    @Query("""
        SELECT e
        FROM RFQ e
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
        """)
    Page<RFQ> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        Pageable pageable
    );

    @Query("""
        SELECT e
        FROM RFQ e
        WHERE e.tenantId = :tenantId
          AND (:companyId IS NULL OR e.companyId = :companyId)
          AND e.isDeleted = false
          AND (:search IS NULL OR
               LOWER(COALESCE(e.code, '')) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(COALESCE(e.name, '')) LIKE LOWER(CONCAT('%', :search, '%')))
          AND NOT EXISTS (
              SELECT 1
              FROM TenderCommission tc
              WHERE tc.rfqId = e.id
                AND tc.tenantId = e.tenantId
                AND tc.active = true
                AND tc.isDeleted = false
          )
        """)
    Page<RFQ> searchUnassignedToTenderCommission(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("search") String search,
        Pageable pageable
    );
}
