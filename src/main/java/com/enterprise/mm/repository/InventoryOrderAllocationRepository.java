package com.enterprise.mm.repository;

import com.enterprise.mm.entity.InventoryOrderAllocation;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryOrderAllocationRepository extends JpaRepository<InventoryOrderAllocation, UUID> {
    List<InventoryOrderAllocation> findByOrderIdOrderByCreatedAtAsc(UUID orderId);
    Optional<InventoryOrderAllocation> findByIdAndOrderId(UUID id, UUID orderId);
    @Query("""
        select a from InventoryOrderAllocation a, InventoryOrder o
        where a.orderId = o.id and o.tenantId = :tenant and o.companyId = :company
          and a.approverUserId = :userId and a.status = 'SUBMITTED'
        order by a.createdAt desc
        """)
    List<InventoryOrderAllocation> findAssignedSubmitted(@Param("tenant") String tenant,
        @Param("company") UUID company, @Param("userId") UUID userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select allocation from InventoryOrderAllocation allocation where allocation.id = :id and allocation.orderId = :orderId")
    Optional<InventoryOrderAllocation> lock(@Param("id") UUID id, @Param("orderId") UUID orderId);
}
