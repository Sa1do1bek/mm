package com.enterprise.mm.warehouse.custody;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WarehouseCustodyActivationRepository extends JpaRepository<WarehouseCustodyActivation, UUID> {

    Optional<WarehouseCustodyActivation> findByTenantIdAndCompanyIdAndSourceReferenceId(
        String tenantId, UUID companyId, String sourceReferenceId
    );
}
