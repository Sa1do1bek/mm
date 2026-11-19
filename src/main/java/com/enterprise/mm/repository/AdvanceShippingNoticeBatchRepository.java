package com.enterprise.mm.repository;

import com.enterprise.mm.entity.AdvanceShippingNoticeBatch;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdvanceShippingNoticeBatchRepository extends JpaRepository<AdvanceShippingNoticeBatch, UUID> {
    List<AdvanceShippingNoticeBatch> findByTenantIdAndCompanyIdAndAsnItemIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId,
        UUID companyId,
        UUID asnItemId
    );
}
