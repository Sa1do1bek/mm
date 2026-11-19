package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ApprovalHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalHistoryRepository extends JpaRepository<ApprovalHistory, UUID> {
    List<ApprovalHistory> findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByActionAtAsc(String tenantId, UUID approvalInstanceId);
}
