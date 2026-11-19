package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.PurchaseRequest;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;

public interface PurchaseRequestRepository extends JpaRepository<PurchaseRequest, UUID>, JpaSpecificationExecutor<PurchaseRequest> {
    Optional<PurchaseRequest> findAnyById(UUID id);
    Optional<PurchaseRequest> findByIdAndIsDeletedFalse(UUID id);
    long countByTenantIdAndStatusInAndIsDeletedFalse(String tenantId, List<String> statuses);

    long countByTenantIdAndCompanyIdAndStatusInAndIsDeletedFalse(String tenantId, UUID companyId, List<String> statuses);

    List<PurchaseRequest> findByTenantIdAndIsDeletedFalse(String tenantId);

    List<PurchaseRequest> findByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId);

    List<PurchaseRequest> findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        Collection<UUID> ids
    );
    List<PurchaseRequest> findByTenantIdAndCompanyIdAndDemandPlanIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID demandPlanId
    );
    List<PurchaseRequest> findByTenantIdAndCompanyIdAndDemandPlanIdInAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        Collection<UUID> demandPlanIds
    );

    @Query("""
        select request
        from PurchaseRequest request
        where request.isDeleted = false
          and request.tenantId = :tenantId
          and request.companyId = :companyId
          and request.createdAt between :periodStart and :periodEnd
        order by request.createdAt, request.id
        """)
    List<PurchaseRequest> findReportWindow(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("periodStart") LocalDateTime periodStart,
        @Param("periodEnd") LocalDateTime periodEnd,
        Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PurchaseRequest p where p.id in :ids order by p.id")
    List<PurchaseRequest> findAllByIdInForUpdate(@Param("ids") Collection<UUID> ids);

    @Query("""
        select coalesce(sum(request.totalAmount), 0) from PurchaseRequest request
        where request.isDeleted = false
          and request.tenantId = :tenantId
        """)
    BigDecimal sumTotalAmountByTenant(@Param("tenantId") String tenantId);

    @Query("""
        select coalesce(sum(request.totalAmount), 0) from PurchaseRequest request
        where request.isDeleted = false
          and request.tenantId = :tenantId
          and request.companyId = :companyId
        """)
    BigDecimal sumTotalAmountByTenantAndCompany(@Param("tenantId") String tenantId, @Param("companyId") UUID companyId);

    @Query("""
        select coalesce(sum(request.totalAmount), 0) from PurchaseRequest request
        where request.isDeleted = false
          and request.tenantId = :tenantId
          and request.status in :statuses
        """)
    BigDecimal sumTotalAmountByTenantAndStatusIn(
        @Param("tenantId") String tenantId,
        @Param("statuses") List<String> statuses
    );

    @Query("""
        select coalesce(sum(request.totalAmount), 0) from PurchaseRequest request
        where request.isDeleted = false
          and request.tenantId = :tenantId
          and request.companyId = :companyId
          and request.status in :statuses
        """)
    BigDecimal sumTotalAmountByTenantAndCompanyAndStatusIn(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("statuses") List<String> statuses
    );

    @Query("""
        SELECT e
        FROM PurchaseRequest e
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
    Page<PurchaseRequest> search(
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
}
