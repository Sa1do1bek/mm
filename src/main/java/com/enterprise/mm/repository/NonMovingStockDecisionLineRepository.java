package com.enterprise.mm.repository;

import com.enterprise.mm.entity.NonMovingStockDecisionLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NonMovingStockDecisionLineRepository extends JpaRepository<NonMovingStockDecisionLine, UUID> {
    List<NonMovingStockDecisionLine> findByTenantIdAndDecisionIdAndIsDeletedFalseOrderByLineOrderAsc(
        String tenantId,
        UUID decisionId
    );

    List<NonMovingStockDecisionLine> findByTenantIdAndDecisionIdInAndIsDeletedFalseOrderByDecisionIdAscLineOrderAsc(
        String tenantId,
        List<UUID> decisionIds
    );
}
