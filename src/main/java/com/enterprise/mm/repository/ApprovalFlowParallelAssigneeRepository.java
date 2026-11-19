package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ApprovalFlowParallelAssignee;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalFlowParallelAssigneeRepository extends JpaRepository<ApprovalFlowParallelAssignee, UUID> {
    Optional<ApprovalFlowParallelAssignee> findByIdAndIsDeletedFalse(UUID id);
    List<ApprovalFlowParallelAssignee> findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByAssigneeUsername(
        String tenantId,
        UUID approvalFlowId
    );
}
