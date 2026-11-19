package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ManualUnmatchedIssue;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManualUnmatchedIssueRepository extends JpaRepository<ManualUnmatchedIssue, UUID> {
    Optional<ManualUnmatchedIssue> findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID id);
    List<ManualUnmatchedIssue> findTop50ByTenantIdAndCompanyIdAndEntryContextWarehouseIdAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId, UUID companyId, UUID entryContextWarehouseId);
}
