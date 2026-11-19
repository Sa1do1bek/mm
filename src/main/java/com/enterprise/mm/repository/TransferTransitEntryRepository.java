package com.enterprise.mm.repository;

import com.enterprise.mm.entity.TransferTransitEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Query;

public interface TransferTransitEntryRepository extends Repository<TransferTransitEntry, UUID> {
    TransferTransitEntry saveAndFlush(TransferTransitEntry entry);
    List<TransferTransitEntry> findByTenantIdAndCompanyIdAndAllocationIdOrderByCreatedAtAscIdAsc(String tenantId, UUID companyId, UUID allocationId);
    @Query("""
        select coalesce(sum(e.valueDelta), 0) from TransferTransitEntry e
        join TransferShipmentAllocation a on a.id = e.allocationId
        where e.tenantId = :tenantId and e.companyId = :companyId
          and (:warehouseId is null or a.sourceWarehouseId = :warehouseId)
        """)
    java.math.BigDecimal sumBookValue(String tenantId, UUID companyId, UUID warehouseId);
}
