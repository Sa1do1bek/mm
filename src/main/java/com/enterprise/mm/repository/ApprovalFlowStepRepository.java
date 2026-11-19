package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ApprovalFlowStep;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalFlowStepRepository extends JpaRepository<ApprovalFlowStep, UUID> {
    Optional<ApprovalFlowStep> findByIdAndIsDeletedFalse(UUID id);
    List<ApprovalFlowStep> findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByStepOrder(String tenantId, UUID approvalFlowId);
    List<ApprovalFlowStep> findByTenantIdAndApprovalFlowIdOrderByStepOrder(String tenantId, UUID approvalFlowId);
}
