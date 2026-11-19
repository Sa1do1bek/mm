package com.enterprise.mm.warehouse.installation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WarehouseInstallationPositionRepository extends JpaRepository<WarehouseInstallationPosition, UUID> {

    List<WarehouseInstallationPosition> findByTenantIdAndCompanyIdAndProjectId(String tenantId, String companyId, UUID projectId);

    List<WarehouseInstallationPosition> findByTenantIdAndCompanyIdAndProjectIdAndMaterialId(
        String tenantId, String companyId, UUID projectId, UUID materialId
    );

    Optional<WarehouseInstallationPosition> findByTenantIdAndCompanyIdAndProjectIdAndMaterialIdAndWarehouseId(
        String tenantId, String companyId, UUID projectId, UUID materialId, UUID warehouseId
    );
}
