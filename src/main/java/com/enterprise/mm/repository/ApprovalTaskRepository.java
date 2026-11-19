package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ApprovalTask;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApprovalTaskRepository extends JpaRepository<ApprovalTask, UUID> {
    Optional<ApprovalTask> findByIdAndIsDeletedFalse(UUID id);
    Optional<ApprovalTask> findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID id
    );
    Optional<ApprovalTask> findFirstByTenantIdAndApprovalInstanceIdAndTaskStatusAndIsDeletedFalseOrderByStepOrder(
        String tenantId,
        UUID approvalInstanceId,
        ApprovalTaskStatus status
    );
    List<ApprovalTask> findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(String tenantId, UUID approvalInstanceId);
    List<ApprovalTask> findByTenantIdAndCompanyIdAndTaskStatusAndAssigneeRoleAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId,
        UUID companyId,
        ApprovalTaskStatus status,
        String assigneeRole
    );
    List<ApprovalTask> findByTenantIdAndCompanyIdAndTaskStatusAndAssigneeUsernameAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId,
        UUID companyId,
        ApprovalTaskStatus status,
        String assigneeUsername
    );

    @Query("""
        select t from ApprovalTask t
        where t.tenantId = :tenantId
          and t.companyId = :companyId
          and t.isDeleted = false
          and upper(trim(coalesce(t.assigneeUsername, ''))) = upper(trim(:recipientUsername))
        order by t.createdAt desc
        """)
    List<ApprovalTask> findNavigationCandidatesForUser(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("recipientUsername") String recipientUsername
    );

    @Query("""
        select t from ApprovalTask t
        where t.tenantId = :tenantId
          and t.companyId = :companyId
          and t.isDeleted = false
          and (
            upper(trim(coalesce(t.assigneeUsername, ''))) = upper(trim(:recipientUsername))
            or (
              t.assigneeType = com.enterprise.mm.enums.ApprovalAssigneeType.ROLE
              and upper(trim(coalesce(t.assigneeRole, ''))) in :roleNames
            )
          )
        order by t.createdAt desc
        """)
    List<ApprovalTask> findNavigationCandidates(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("recipientUsername") String recipientUsername,
        @Param("roleNames") Collection<String> roleNames
    );
}
