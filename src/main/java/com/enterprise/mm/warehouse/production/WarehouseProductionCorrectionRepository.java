package com.enterprise.mm.warehouse.production;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WarehouseProductionCorrectionRepository extends JpaRepository<WarehouseProductionCorrection, UUID> {

    List<WarehouseProductionCorrection> findByTenantIdAndCompanyIdAndParentEventId(
        String tenantId, String companyId, String parentEventId
    );

    Optional<WarehouseProductionCorrection> findByTenantIdAndCompanyIdAndCorrectionEventId(
        String tenantId, String companyId, String correctionEventId
    );

    List<WarehouseProductionCorrection> findByTenantIdAndCompanyIdAndMaterialId(
        String tenantId, String companyId, UUID materialId
    );
}
