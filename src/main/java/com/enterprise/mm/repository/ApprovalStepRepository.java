package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.ApprovalStep;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApprovalStepRepository extends JpaRepository<ApprovalStep, UUID> {
    Optional<ApprovalStep> findAnyById(UUID id);
    Optional<ApprovalStep> findByIdAndIsDeletedFalse(UUID id);
    List<ApprovalStep> findByTenantIdAndApprovalWorkflowIdAndIsDeletedFalseOrderByStepNumber(String tenantId, UUID approvalWorkflowId);

    @Query("""
        SELECT e
        FROM ApprovalStep e
        WHERE
            e.tenantId = :tenantId
            AND (:companyId IS NULL OR e.companyId = :companyId)
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (:hasSearch = false OR
                 LOWER(COALESCE(e.code, '')) LIKE :searchPattern
                 OR LOWER(COALESCE(e.name, '')) LIKE :searchPattern)
            AND (CAST(:dateFrom AS timestamp) IS NULL OR e.createdAt >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR e.createdAt <= :dateTo)
            AND (:statusEmpty = true OR e.status IN :status)
            AND (:approvalWorkflowId IS NULL OR e.approvalWorkflowId = :approvalWorkflowId)
        """)
    Page<ApprovalStep> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("hasSearch") boolean hasSearch,
        @Param("searchPattern") String searchPattern,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("approvalWorkflowId") UUID approvalWorkflowId,
        Pageable pageable
    );
}
