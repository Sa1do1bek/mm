package com.enterprise.mm.repository;

import com.enterprise.mm.entity.SupplierPoCommitment;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SupplierPoCommitmentRepository
    extends JpaRepository<SupplierPoCommitment, UUID>, JpaSpecificationExecutor<SupplierPoCommitment> {

    Optional<SupplierPoCommitment> findByIdAndIsDeletedFalse(UUID id);

    List<SupplierPoCommitment> findByTenantIdAndCompanyIdAndResponseLineIdAndCurrentTrueAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID responseLineId
    );

    List<SupplierPoCommitment> findByTenantIdAndCompanyIdAndResponseLineIdAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID responseLineId
    );

    /** Open (not yet FULFILLED/CANCELLED/SUPERSEDED) current commitments for a PO item --
     * Phase B uses this to compute ASN-remaining-quantity. */
    List<SupplierPoCommitment> findByTenantIdAndCompanyIdAndPurchaseOrderItemIdAndCurrentTrueAndStatusInAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID purchaseOrderItemId, List<String> statuses
    );

    List<SupplierPoCommitment> findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndStatusInAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID purchaseOrderId, List<String> statuses
    );

    Optional<SupplierPoCommitment> findByTenantIdAndCompanyIdAndDeliveryScheduleIdAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID deliveryScheduleId
    );

    List<SupplierPoCommitment> findByTenantIdAndCompanyIdAndSupersededCommitmentIdAndCurrentTrueAndStatusInAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID supersededCommitmentId, List<String> statuses
    );

    /** Cross-tenant candidates for the commitment due-soon / overdue / ASN-missing
     * reminder scheduler -- narrowed further in-memory by dedup timestamp. */
    List<SupplierPoCommitment> findByStatusAndCurrentTrueAndConfirmedDateIsNotNullAndConfirmedDateLessThanEqualAndIsDeletedFalse(
        String status, LocalDate date
    );
}
