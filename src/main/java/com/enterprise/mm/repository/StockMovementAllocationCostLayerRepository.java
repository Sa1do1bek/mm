package com.enterprise.mm.repository;

import com.enterprise.mm.entity.StockMovementAllocationCostLayer;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface StockMovementAllocationCostLayerRepository
    extends Repository<StockMovementAllocationCostLayer, UUID> {

    @Query("""
        select lineage
        from StockMovementAllocationCostLayer lineage
        join StockMovementAllocation allocation on allocation.id = lineage.allocationId
        where lineage.tenantId = :tenantId
          and lineage.companyId = :companyId
          and allocation.tenantId = :tenantId
          and allocation.companyId = :companyId
          and allocation.movementId = :movementId
        order by allocation.allocationSequence, allocation.id, lineage.lineageSequence, lineage.id
        """)
    List<StockMovementAllocationCostLayer> findByTenantIdAndCompanyIdAndMovementId(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("movementId") UUID movementId
    );
}
