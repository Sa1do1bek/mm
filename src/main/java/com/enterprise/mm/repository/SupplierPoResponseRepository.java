package com.enterprise.mm.repository;

import com.enterprise.mm.entity.SupplierPoResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SupplierPoResponseRepository
    extends JpaRepository<SupplierPoResponse, UUID>, JpaSpecificationExecutor<SupplierPoResponse> {

    Optional<SupplierPoResponse> findByIdAndIsDeletedFalse(UUID id);

    Optional<SupplierPoResponse> findByTenantIdAndCompanyIdAndDispatchIdAndCurrentTrueAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID dispatchId
    );

    List<SupplierPoResponse> findByTenantIdAndCompanyIdAndDispatchIdAndIsDeletedFalseOrderByResponseRevisionDesc(
        String tenantId, UUID companyId, UUID dispatchId
    );

    List<SupplierPoResponse> findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId, UUID companyId, UUID purchaseOrderId
    );

    /** The single current response for a PO (across whichever dispatch is current) --
     * used by {@code PurchaseOrderService.close()} to block close while a supplier
     * exception (REVISION_REQUESTED) is unresolved. */
    Optional<SupplierPoResponse> findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID purchaseOrderId
    );

    Optional<SupplierPoResponse> findByTenantIdAndCompanyIdAndReconfirmationOriginCommitmentIdAndCurrentTrueAndResponseStatusInAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID reconfirmationOriginCommitmentId, List<String> responseStatuses
    );

    long countByTenantIdAndCompanyIdAndDispatchIdAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID dispatchId
    );
}
