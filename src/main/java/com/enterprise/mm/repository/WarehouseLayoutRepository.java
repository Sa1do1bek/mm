package com.enterprise.mm.repository;

import com.enterprise.mm.entity.WarehouseLayout;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseLayoutRepository extends JpaRepository<WarehouseLayout, UUID> {
    Optional<WarehouseLayout> findByTenantIdAndCompanyIdAndWarehouseId(String tenantId, UUID companyId, UUID warehouseId);
}
