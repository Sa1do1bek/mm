package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ContractInvoice;
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
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

public interface ContractInvoiceRepository extends JpaRepository<ContractInvoice, UUID>, JpaSpecificationExecutor<ContractInvoice> {
    Optional<ContractInvoice> findAnyById(UUID id);
    Optional<ContractInvoice> findByIdAndIsDeletedFalse(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from ContractInvoice e where e.id = :id and e.isDeleted = false")
    Optional<ContractInvoice> findByIdForUpdate(@Param("id") UUID id);

    List<ContractInvoice> findByTenantIdAndContract_IdAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, UUID contractId);
    List<ContractInvoice> findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, UUID purchaseOrderId);
    List<ContractInvoice> findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId, UUID companyId, UUID purchaseOrderId);
    Optional<ContractInvoice> findFirstByTenantIdAndCompanyIdAndPurchaseOrderIdAndStatusAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId, UUID companyId, UUID purchaseOrderId, String status);
    Optional<ContractInvoice> findByTenantIdAndCodeAndIsDeletedFalse(String tenantId, String code);

    @Query("""
        SELECT e
        FROM ContractInvoice e
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
        """)
    Page<ContractInvoice> search(
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
        Pageable pageable
    );
}
