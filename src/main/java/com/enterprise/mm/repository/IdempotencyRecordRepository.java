package com.enterprise.mm.repository;

import com.enterprise.mm.entity.IdempotencyRecord;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {
    Optional<IdempotencyRecord> findByTenantIdAndCompanyIdAndOperationAndResourceIdAndIdempotencyKeyAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        String operation,
        UUID resourceId,
        String idempotencyKey
    );
}
