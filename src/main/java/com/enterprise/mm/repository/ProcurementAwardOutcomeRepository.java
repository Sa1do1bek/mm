package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ProcurementAwardOutcome;
import com.enterprise.mm.enums.ProcurementAwardSourceType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcurementAwardOutcomeRepository extends JpaRepository<ProcurementAwardOutcome, UUID> {
    Optional<ProcurementAwardOutcome>
        findByTenantIdAndCompanyIdAndProcurementProcedureIdAndSourceTypeAndIdempotencyKeyAndIsDeletedFalse(
            String tenantId,
            UUID companyId,
            UUID procurementProcedureId,
            ProcurementAwardSourceType sourceType,
            String idempotencyKey
        );

    Optional<ProcurementAwardOutcome>
        findFirstByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByFinalizedAtDesc(
            String tenantId,
            UUID companyId,
            UUID purchaseOrderId
        );

    boolean existsByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID purchaseOrderId
    );

    Optional<ProcurementAwardOutcome>
        findFirstByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalseOrderByFinalizedAtDesc(
            String tenantId,
            UUID companyId,
            UUID procurementProcedureId
        );
}
