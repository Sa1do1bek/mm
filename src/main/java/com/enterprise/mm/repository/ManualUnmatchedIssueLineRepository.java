package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ManualUnmatchedIssueLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManualUnmatchedIssueLineRepository extends JpaRepository<ManualUnmatchedIssueLine, UUID> {
    List<ManualUnmatchedIssueLine> findByIssueIdAndIsDeletedFalseOrderByLineNumberAsc(UUID issueId);
}
