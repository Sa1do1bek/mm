package com.enterprise.mm.repository;

import com.enterprise.mm.entity.IssueRequestLine;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IssueRequestLineRepository extends JpaRepository<IssueRequestLine, UUID> {
    Optional<IssueRequestLine> findByIdAndIsDeletedFalse(UUID id);
    List<IssueRequestLine> findByTenantIdAndIssueRequestIdAndIsDeletedFalseOrderByCreatedAtAsc(String tenantId, UUID issueRequestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select line from IssueRequestLine line where line.id = :id and line.isDeleted = false")
    Optional<IssueRequestLine> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
        select line
        from IssueRequestLine line
        join fetch line.issueRequest request
        where line.tenantId = :tenantId
          and line.companyId = :companyId
          and line.sourceDemandPlanItemId in :sourceDemandPlanItemIds
          and line.isDeleted = false
          and request.tenantId = :tenantId
          and request.companyId = :companyId
          and request.isDeleted = false
        order by line.createdAt asc
        """)
    List<IssueRequestLine> findWorkspaceDemandSourceLines(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("sourceDemandPlanItemIds") List<UUID> sourceDemandPlanItemIds);
}
