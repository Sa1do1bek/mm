package com.enterprise.mm.repository;

import com.enterprise.mm.entity.DemandPlanItemPurchaseRequestAllocation;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DemandPlanItemPurchaseRequestAllocationRepository
    extends JpaRepository<DemandPlanItemPurchaseRequestAllocation, UUID>,
        JpaSpecificationExecutor<DemandPlanItemPurchaseRequestAllocation> {

    List<DemandPlanItemPurchaseRequestAllocation> findByTenantIdAndCompanyIdAndDemandPlanItemIdInAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId,
        UUID companyId,
        Collection<UUID> demandPlanItemIds
    );

    List<DemandPlanItemPurchaseRequestAllocation> findByTenantIdAndCompanyIdAndPurchaseRequestItemIdInAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId,
        UUID companyId,
        Collection<UUID> purchaseRequestItemIds
    );
}
