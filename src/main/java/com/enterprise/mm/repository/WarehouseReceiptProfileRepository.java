package com.enterprise.mm.repository;

import com.enterprise.mm.entity.WarehouseReceiptProfile;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseReceiptProfileRepository extends JpaRepository<WarehouseReceiptProfile, UUID> {
    Optional<WarehouseReceiptProfile> findByTenantIdAndCompanyIdAndWarehouseId(String tenantId, UUID companyId, UUID warehouseId);
}
