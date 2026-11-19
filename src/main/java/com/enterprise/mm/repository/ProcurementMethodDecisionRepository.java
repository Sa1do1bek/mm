package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ProcurementMethodDecision;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ProcurementMethodDecisionRepository extends JpaRepository<ProcurementMethodDecision, UUID>, JpaSpecificationExecutor<ProcurementMethodDecision> {
    Optional<ProcurementMethodDecision> findAnyById(UUID id);
    Optional<ProcurementMethodDecision> findByIdAndIsDeletedFalse(UUID id);
    Optional<ProcurementMethodDecision> findByIdAndTenantIdAndCompanyIdAndIsDeletedFalse(UUID id, String tenantId, UUID companyId);
    Optional<ProcurementMethodDecision> findByTenantIdAndCompanyIdAndDecisionNumberAndIsDeletedFalse(String tenantId, UUID companyId, String decisionNumber);
    Optional<ProcurementMethodDecision> findFirstByTenantIdAndCompanyIdAndPurchaseRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId,
        UUID companyId,
        UUID purchaseRequestId
    );
}
