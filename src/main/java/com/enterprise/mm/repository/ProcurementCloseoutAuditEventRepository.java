package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ProcurementCloseoutAuditEvent;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcurementCloseoutAuditEventRepository extends JpaRepository<ProcurementCloseoutAuditEvent, UUID> {
    Optional<ProcurementCloseoutAuditEvent> findByTenantIdAndCompanyIdAndIdempotencyKeyAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        String idempotencyKey
    );
}
