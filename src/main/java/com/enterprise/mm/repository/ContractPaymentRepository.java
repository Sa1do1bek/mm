package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ContractPayment;
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

public interface ContractPaymentRepository extends JpaRepository<ContractPayment, UUID>, JpaSpecificationExecutor<ContractPayment> {
    Optional<ContractPayment> findAnyById(UUID id);
    Optional<ContractPayment> findByIdAndIsDeletedFalse(UUID id);
    List<ContractPayment> findByTenantIdAndContract_IdAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, UUID contractId);
    List<ContractPayment> findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, UUID purchaseOrderId);
    Optional<ContractPayment> findByTenantIdAndCodeAndIsDeletedFalse(String tenantId, String code);
    List<ContractPayment> findByTenantIdAndContractInvoiceIdAndIsDeletedFalseOrderByCreatedAtAsc(String tenantId, UUID contractInvoiceId);
    List<ContractPayment> findByTenantIdAndCompanyIdAndContractInvoiceIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId, UUID companyId, UUID contractInvoiceId);
    List<ContractPayment> findByTenantIdAndSourceDocumentTypeAndSourceDocumentIdAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId, String sourceDocumentType, UUID sourceDocumentId);

    @Query("""
        SELECT e
        FROM ContractPayment e
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
    Page<ContractPayment> search(
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
