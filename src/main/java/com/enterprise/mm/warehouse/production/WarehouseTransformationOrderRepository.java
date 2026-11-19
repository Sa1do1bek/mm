package com.enterprise.mm.warehouse.production;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WarehouseTransformationOrderRepository extends JpaRepository<WarehouseTransformationOrder, UUID> {

    Optional<WarehouseTransformationOrder> findByTenantIdAndCompanyIdAndOrderNumber(
        String tenantId, String companyId, String orderNumber
    );

    List<WarehouseTransformationOrder> findByTenantIdAndCompanyIdAndProcessorId(
        String tenantId, String companyId, UUID processorId
    );
}
