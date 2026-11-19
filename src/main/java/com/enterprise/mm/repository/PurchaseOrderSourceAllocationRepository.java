package com.enterprise.mm.repository;

import com.enterprise.mm.entity.PurchaseOrderSourceAllocation;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseOrderSourceAllocationRepository
    extends JpaRepository<PurchaseOrderSourceAllocation, UUID>, JpaSpecificationExecutor<PurchaseOrderSourceAllocation> {

    List<PurchaseOrderSourceAllocation> findByTenantIdAndCompanyIdAndPurchaseOrderItemIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId, UUID companyId, UUID purchaseOrderItemId);

    List<PurchaseOrderSourceAllocation> findByTenantIdAndCompanyIdAndPurchaseOrderItemIdInAndIsDeletedFalse(
        String tenantId, UUID companyId, List<UUID> purchaseOrderItemIds);

    List<PurchaseOrderSourceAllocation> findByTenantIdAndCompanyIdAndPurchaseRequestItemIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId, UUID companyId, UUID purchaseRequestItemId);

    List<PurchaseOrderSourceAllocation> findByTenantIdAndCompanyIdAndPurchaseRequestItemIdInAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId, UUID companyId, List<UUID> purchaseRequestItemIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select allocation
        from PurchaseOrderSourceAllocation allocation
        where allocation.tenantId = :tenantId
          and allocation.companyId = :companyId
          and allocation.purchaseOrderItemId in :purchaseOrderItemIds
          and allocation.isDeleted = false
        order by allocation.purchaseOrderItemId, allocation.purchaseRequestItemId, allocation.id
        """)
    List<PurchaseOrderSourceAllocation> findByPurchaseOrderItemIdsForUpdate(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("purchaseOrderItemIds") List<UUID> purchaseOrderItemIds);
}
