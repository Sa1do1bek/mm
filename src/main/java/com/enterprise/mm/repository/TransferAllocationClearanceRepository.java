package com.enterprise.mm.repository;

import com.enterprise.mm.entity.TransferAllocationClearance;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Append-only facts: no delete or update API. */
public interface TransferAllocationClearanceRepository extends Repository<TransferAllocationClearance, UUID> {
    TransferAllocationClearance saveAndFlush(TransferAllocationClearance value);
    TransferAllocationClearance save(TransferAllocationClearance value);
    Optional<TransferAllocationClearance> findById(UUID id);
    List<TransferAllocationClearance> findByTenantIdAndCompanyIdAndAllocationIdOrderByCreatedAtAscIdAsc(String tenantId, UUID companyId, UUID allocationId);
}
