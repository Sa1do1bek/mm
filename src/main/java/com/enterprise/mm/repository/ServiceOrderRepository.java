package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ServiceOrder;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import jakarta.persistence.LockModeType;

public interface ServiceOrderRepository extends JpaRepository<ServiceOrder, UUID>, JpaSpecificationExecutor<ServiceOrder> {
    Optional<ServiceOrder> findAnyById(UUID id);
    Optional<ServiceOrder> findByIdAndIsDeletedFalse(UUID id);
    Optional<ServiceOrder> findByTenantIdAndIdAndIsDeletedFalse(String tenantId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from ServiceOrder e where e.tenantId = :tenantId and e.id = :id and e.isDeleted = false")
    Optional<ServiceOrder> findByTenantIdAndIdForAcceptanceUpdate(
        @Param("tenantId") String tenantId,
        @Param("id") UUID id
    );
    List<ServiceOrder> findByTenantIdAndCompanyIdAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, UUID companyId);
    List<ServiceOrder> findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, UUID companyId, UUID serviceRequestId);
    List<ServiceOrder> findByTenantIdAndCompanyIdAndPurchaseOrderIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId, UUID companyId, UUID purchaseOrderId, UUID supplierId);
    Optional<ServiceOrder> findFirstByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID purchaseOrderId);

    @Query("""
        SELECT e
        FROM ServiceOrder e
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
    Page<ServiceOrder> search(
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
