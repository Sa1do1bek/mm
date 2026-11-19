package com.enterprise.mm.repository;

import com.enterprise.mm.entity.NonMovingStockDecision;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NonMovingStockDecisionRepository extends JpaRepository<NonMovingStockDecision, UUID> {
    Optional<NonMovingStockDecision> findByIdAndIsDeletedFalse(UUID id);
    List<NonMovingStockDecision> findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(String tenantId, UUID caseId);

    @Query("""
        select d
        from NonMovingStockDecision d
        where d.tenantId = :tenantId
          and d.companyId = :companyId
          and d.isDeleted = false
          and d.status = 'DECISION_APPROVED'
          and d.decisionType in ('SELL', 'WRITE_OFF')
        """)
    List<NonMovingStockDecision> findManagementOpportunityRecoveryGapCandidates(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId
    );
}
