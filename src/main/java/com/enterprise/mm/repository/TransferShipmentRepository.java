package com.enterprise.mm.repository;

import com.enterprise.mm.entity.TransferShipment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Append-only facts: no delete or update API. */
public interface TransferShipmentRepository extends Repository<TransferShipment, UUID> {
    TransferShipment saveAndFlush(TransferShipment value);
    TransferShipment save(TransferShipment value);
    Optional<TransferShipment> findById(UUID id);
    List<TransferShipment> findByTenantIdAndCompanyIdAndTransferDocumentIdOrderByCreatedAtAscIdAsc(String tenantId, UUID companyId, UUID transferDocumentId);
}
