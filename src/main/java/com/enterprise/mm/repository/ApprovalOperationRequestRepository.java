package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ApprovalOperationRequest;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ApprovalOperationRequestRepository extends JpaRepository<ApprovalOperationRequest, UUID> {
    Optional<ApprovalOperationRequest> findByIdAndIsDeletedFalse(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ApprovalOperationRequest> findForUpdateByIdAndIsDeletedFalse(UUID id);

    Optional<ApprovalOperationRequest> findByTenantIdAndCompanyIdAndRequesterUsernameAndIdempotencyKeyAndIsDeletedFalse(
        String tenantId, UUID companyId, String requesterUsername, String idempotencyKey
    );

    Optional<ApprovalOperationRequest> findFirstByTenantIdAndCompanyIdAndModuleKeyAndTargetEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId, UUID companyId, String moduleKey, UUID targetEntityId
    );

    boolean existsByTenantIdAndCompanyIdAndModuleKeyAndTargetEntityIdAndStatusInAndIsDeletedFalse(
        String tenantId, UUID companyId, String moduleKey, UUID targetEntityId, java.util.Collection<String> statuses
    );
}
