package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ProcurementFrameworkLedgerEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcurementFrameworkLedgerRepository extends JpaRepository<ProcurementFrameworkLedgerEntry, UUID> {
    List<ProcurementFrameworkLedgerEntry> findByTenantIdAndCompanyIdAndFrameworkIdAndIdempotencyKeyAndIsDeletedFalseOrderByOccurredAtAsc(
        String tenantId, UUID companyId, UUID frameworkId, String idempotencyKey);
    List<ProcurementFrameworkLedgerEntry> findByTenantIdAndCompanyIdAndFrameworkIdAndIsDeletedFalseOrderByOccurredAtAsc(
        String tenantId, UUID companyId, UUID frameworkId);
}
