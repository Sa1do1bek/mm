package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ManagementOpportunityEvidence;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManagementOpportunityEvidenceRepository extends JpaRepository<ManagementOpportunityEvidence, UUID> {
    Optional<ManagementOpportunityEvidence> findByIdAndIsDeletedFalse(UUID id);
    List<ManagementOpportunityEvidence> findByTenantIdAndOpportunityIdAndIsDeletedFalseOrderByCreatedAtAsc(String tenantId, UUID opportunityId);
    List<ManagementOpportunityEvidence> findByTenantIdAndOpportunityIdInAndIsDeletedFalseOrderByCreatedAtAsc(String tenantId, List<UUID> opportunityIds);
}
