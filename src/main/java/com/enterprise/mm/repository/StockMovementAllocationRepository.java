package com.enterprise.mm.repository;

import com.enterprise.mm.entity.StockMovementAllocation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface StockMovementAllocationRepository extends Repository<StockMovementAllocation, UUID> {
    @Query("""
        select allocation
        from StockMovementAllocation allocation
        where allocation.tenantId = :tenantId
          and allocation.companyId = :companyId
          and allocation.movementId = :movementId
        order by allocation.allocationSequence, allocation.id
        """)
    List<StockMovementAllocation> findByTenantIdAndCompanyIdAndMovementId(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("movementId") UUID movementId
    );
}
