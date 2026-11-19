package com.enterprise.mm.repository;

import com.enterprise.mm.entity.NonMovingStockExecution;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NonMovingStockExecutionRepository extends JpaRepository<NonMovingStockExecution, UUID> {
    List<NonMovingStockExecution> findByTenantIdAndCaseIdAndIsDeletedFalse(String tenantId, UUID caseId);
    List<NonMovingStockExecution> findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(String tenantId, UUID caseId);
    Optional<NonMovingStockExecution> findByIdAndIsDeletedFalse(UUID id);
    Optional<NonMovingStockExecution> findByTenantIdAndCompanyIdAndIdempotencyKeyAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        String idempotencyKey
    );
}
