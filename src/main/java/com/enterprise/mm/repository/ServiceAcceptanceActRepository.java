package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ServiceAcceptanceAct;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ServiceAcceptanceActRepository extends JpaRepository<ServiceAcceptanceAct, UUID>, JpaSpecificationExecutor<ServiceAcceptanceAct> {
    Optional<ServiceAcceptanceAct> findAnyById(UUID id);
    Optional<ServiceAcceptanceAct> findByIdAndIsDeletedFalse(UUID id);
    Optional<ServiceAcceptanceAct> findByTenantIdAndServiceOrderIdAndIsDeletedFalse(String tenantId, UUID serviceOrderId);
    Optional<ServiceAcceptanceAct> findByTenantIdAndServiceRequestIdAndIsDeletedFalse(String tenantId, UUID serviceRequestId);
    List<ServiceAcceptanceAct> findByTenantIdAndCompanyIdAndServiceOrderIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID serviceOrderId);
    List<ServiceAcceptanceAct> findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID serviceRequestId);
    List<ServiceAcceptanceAct> findByTenantIdAndPurchaseOrderIdAndStatusInAndIsDeletedFalse(String tenantId, UUID purchaseOrderId, List<String> statuses);
    List<ServiceAcceptanceAct> findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID purchaseOrderId);
    List<ServiceAcceptanceAct> findByTenantIdAndCompanyIdAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, UUID companyId);
    List<ServiceAcceptanceAct> findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, UUID companyId, UUID serviceRequestId);

    List<ServiceAcceptanceAct> findByTenantIdAndCompanyIdAndPurchaseOrderIdInAndStatusInAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId, UUID companyId, List<UUID> purchaseOrderIds, List<String> statuses);
    List<ServiceAcceptanceAct> findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId, UUID companyId, UUID purchaseOrderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from ServiceAcceptanceAct e where e.id = :id and e.isDeleted = false")
    Optional<ServiceAcceptanceAct> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
        SELECT e
        FROM ServiceAcceptanceAct e
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
    Page<ServiceAcceptanceAct> search(
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
