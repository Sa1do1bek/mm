package com.enterprise.mm.repository;

import com.enterprise.mm.entity.NonMovingStockExecutionOutput;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NonMovingStockExecutionOutputRepository extends JpaRepository<NonMovingStockExecutionOutput, UUID> {
    Optional<NonMovingStockExecutionOutput> findByIdAndIsDeletedFalse(UUID id);

    List<NonMovingStockExecutionOutput> findByTenantIdAndExecutionIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId,
        UUID executionId
    );

    List<NonMovingStockExecutionOutput> findByTenantIdAndDocumentTypeAndDocumentIdAndIsDeletedFalse(
        String tenantId,
        String documentType,
        UUID documentId
    );

    boolean existsByTenantIdAndDecisionLineIdAndOutputStatusInAndIsDeletedFalse(
        String tenantId,
        UUID decisionLineId,
        Collection<String> outputStatuses
    );
}
