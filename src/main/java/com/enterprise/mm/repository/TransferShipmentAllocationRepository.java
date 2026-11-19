package com.enterprise.mm.repository;

import com.enterprise.mm.entity.TransferShipmentAllocation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Append-only facts: no delete or update API. */
public interface TransferShipmentAllocationRepository extends Repository<TransferShipmentAllocation, UUID> {
    TransferShipmentAllocation saveAndFlush(TransferShipmentAllocation value);
    TransferShipmentAllocation save(TransferShipmentAllocation value);
    Optional<TransferShipmentAllocation> findById(UUID id);
    @org.springframework.data.jpa.repository.Query(value = "select * from transfer_shipment_allocations where id = :id and tenant_id = :tenantId and company_id = :companyId for update", nativeQuery = true)
    Optional<TransferShipmentAllocation> findScopedForUpdate(UUID id, String tenantId, UUID companyId);
    List<TransferShipmentAllocation> findByTenantIdAndCompanyIdAndTransferDocumentIdOrderByCreatedAtAscIdAsc(String tenantId, UUID companyId, UUID transferDocumentId);
}
