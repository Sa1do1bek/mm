package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ProcurementAwardOutcomeLine;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcurementAwardOutcomeLineRepository extends JpaRepository<ProcurementAwardOutcomeLine, UUID> {
    List<ProcurementAwardOutcomeLine> findByTenantIdAndCompanyIdAndProcurementAwardOutcomeIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID procurementAwardOutcomeId
    );

    Optional<ProcurementAwardOutcomeLine>
        findByTenantIdAndCompanyIdAndProcurementAwardOutcomeIdAndIdempotencyKeyAndIsDeletedFalse(
            String tenantId,
            UUID companyId,
            UUID procurementAwardOutcomeId,
            String idempotencyKey
        );

    List<ProcurementAwardOutcomeLine>
        findByTenantIdAndCompanyIdAndPurchaseRequestItemIdInAndIsDeletedFalseOrderByCreatedAtAsc(
            String tenantId,
            UUID companyId,
            Collection<UUID> purchaseRequestItemIds
        );

    List<ProcurementAwardOutcomeLine> findByTenantIdAndCompanyIdAndPurchaseRequestIdInAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        Collection<UUID> purchaseRequestIds
    );
}
