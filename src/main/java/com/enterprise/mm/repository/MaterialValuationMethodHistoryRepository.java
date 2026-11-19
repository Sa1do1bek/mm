package com.enterprise.mm.repository;

import com.enterprise.mm.entity.MaterialValuationMethodHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialValuationMethodHistoryRepository extends JpaRepository<MaterialValuationMethodHistory, UUID> {
    List<MaterialValuationMethodHistory> findByTenantIdAndCompanyIdAndMaterialIdAndIsDeletedFalseOrderByEffectiveAtDesc(
        String tenantId,
        UUID companyId,
        UUID materialId
    );
}
