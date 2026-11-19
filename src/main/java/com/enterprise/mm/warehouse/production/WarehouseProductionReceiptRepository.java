package com.enterprise.mm.warehouse.production;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WarehouseProductionReceiptRepository extends JpaRepository<WarehouseProductionReceipt, UUID> {

    Optional<WarehouseProductionReceipt> findByTenantIdAndCompanyIdAndEventId(
        String tenantId, String companyId, String eventId
    );

    List<WarehouseProductionReceipt> findByTenantIdAndCompanyIdAndMaterialId(
        String tenantId, String companyId, UUID materialId
    );
}
