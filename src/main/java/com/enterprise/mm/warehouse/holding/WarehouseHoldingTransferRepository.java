package com.enterprise.mm.warehouse.holding;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WarehouseHoldingTransferRepository extends JpaRepository<WarehouseHoldingTransfer, UUID> {

    Optional<WarehouseHoldingTransfer> findByTenantIdAndCompanyIdAndIdempotencyKeyAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        String idempotencyKey
    );

    List<WarehouseHoldingTransfer> findByTenantIdAndCompanyIdAndMaterialIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID materialId
    );

    List<WarehouseHoldingTransfer> findBySourceHoldingIdOrTargetHoldingIdAndIsDeletedFalse(
        UUID sourceHoldingId,
        UUID targetHoldingId
    );
}
