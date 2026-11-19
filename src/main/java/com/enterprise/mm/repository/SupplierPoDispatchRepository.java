package com.enterprise.mm.repository;

import com.enterprise.mm.entity.SupplierPoDispatch;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierPoDispatchRepository
    extends JpaRepository<SupplierPoDispatch, UUID>, JpaSpecificationExecutor<SupplierPoDispatch> {

    Optional<SupplierPoDispatch> findByIdAndIsDeletedFalse(UUID id);

    /** Cross-tenant poll batch for the dispatch-mail scheduler (mirrors
     * RfqInvitationDeliveryRepository.findDue's cross-tenant polling style). */
    List<SupplierPoDispatch> findByDispatchStatusAndIsDeletedFalseOrderByQueuedAtAsc(
        String dispatchStatus, Pageable pageable
    );

    List<SupplierPoDispatch> findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId, UUID companyId, UUID purchaseOrderId
    );

    /** Cross-tenant candidates for the "response due soon" / "response overdue"
     * reminder scheduler -- narrowed further in-memory by dedup timestamp. */
    List<SupplierPoDispatch> findByDispatchStatusInAndCurrentTrueAndResponseDueDateLessThanEqualAndIsDeletedFalse(
        List<String> dispatchStatuses, LocalDate dueDate
    );

    Optional<SupplierPoDispatch> findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID purchaseOrderId
    );

    Optional<SupplierPoDispatch> findByTenantIdAndCompanyIdAndPurchaseOrderIdAndPurchaseOrderVersionAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID purchaseOrderId, Long purchaseOrderVersion
    );

    List<SupplierPoDispatch> findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByPurchaseOrderVersionDesc(
        String tenantId, UUID companyId, UUID purchaseOrderId
    );

    List<SupplierPoDispatch> findByTenantIdAndCompanyIdAndSupplierIdAndDispatchStatusAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID supplierId, String dispatchStatus
    );

    @Query("""
        select d
        from SupplierPoDispatch d
        where d.tenantId = :tenantId
          and d.companyId = :companyId
          and d.supplierId = :supplierId
          and d.current = true
          and d.isDeleted = false
          and (d.dispatchStatus is null or d.dispatchStatus not in :terminalStatuses)
        order by d.createdAt desc
        """)
    List<SupplierPoDispatch> findCurrentPortalCandidatesForSupplierNewest(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("supplierId") UUID supplierId,
        @Param("terminalStatuses") List<String> terminalStatuses,
        Pageable pageable
    );
}
