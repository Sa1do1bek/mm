package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalInstanceRepository extends JpaRepository<ApprovalInstance, UUID> {
    Optional<ApprovalInstance> findByIdAndIsDeletedFalse(UUID id);
    Optional<ApprovalInstance> findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        String moduleKey,
        UUID entityId
    );
    Optional<ApprovalInstance> findFirstByTenantIdAndModuleKeyAndEntityIdAndApprovalStatusAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId,
        String moduleKey,
        UUID entityId,
        ApprovalInstanceStatus status
    );

    long countByTenantIdAndCompanyIdAndModuleKeyAndEntityIdInAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        String moduleKey,
        Collection<UUID> entityIds
    );
}
