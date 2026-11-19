package com.enterprise.mm.warehouse.packaging;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReturnablePackagingEventRepository extends JpaRepository<ReturnablePackagingEvent, UUID> {
    List<ReturnablePackagingEvent> findByTenantIdAndCompanyIdAndLotIdAndIsDeletedFalseOrderByCreatedAtDescIdDesc(
        String tenantId, UUID companyId, UUID lotId);
}
