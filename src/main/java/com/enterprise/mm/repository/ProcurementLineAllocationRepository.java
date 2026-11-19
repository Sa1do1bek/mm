package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ProcurementLineAllocation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcurementLineAllocationRepository extends JpaRepository<ProcurementLineAllocation, UUID> {
    List<ProcurementLineAllocation> findByTenantIdAndCompanyIdAndSourceLineIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId, UUID companyId, UUID sourceLineId);

    List<ProcurementLineAllocation> findByTenantIdAndCompanyIdAndTargetLineIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId, UUID companyId, UUID targetLineId);

    List<ProcurementLineAllocation> findByTenantIdAndCompanyIdAndSourceDocumentTypeAndSourceDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId, UUID companyId, String documentType, UUID documentId);

    List<ProcurementLineAllocation> findByTenantIdAndCompanyIdAndTargetDocumentTypeAndTargetDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId, UUID companyId, String documentType, UUID documentId);

    Optional<ProcurementLineAllocation> findByTenantIdAndCompanyIdAndIdempotencyKeyAndIsDeletedFalse(
        String tenantId, UUID companyId, String idempotencyKey);
}
