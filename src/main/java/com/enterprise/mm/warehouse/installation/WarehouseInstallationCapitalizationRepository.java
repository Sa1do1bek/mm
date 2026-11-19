package com.enterprise.mm.warehouse.installation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WarehouseInstallationCapitalizationRepository extends JpaRepository<WarehouseInstallationCapitalization, UUID> {

    List<WarehouseInstallationCapitalization> findByTenantIdAndCompanyIdAndPositionId(
        String tenantId, String companyId, UUID positionId
    );

    List<WarehouseInstallationCapitalization> findByFixedAssetId(UUID fixedAssetId);
}
