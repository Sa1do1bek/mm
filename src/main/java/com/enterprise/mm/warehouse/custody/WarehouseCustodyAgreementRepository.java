package com.enterprise.mm.warehouse.custody;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WarehouseCustodyAgreementRepository extends JpaRepository<WarehouseCustodyAgreement, UUID> {

    List<WarehouseCustodyAgreement> findByTenantIdAndCompanyIdAndOwnerId(
        String tenantId, UUID companyId, UUID ownerId
    );

    Optional<WarehouseCustodyAgreement> findByTenantIdAndCompanyIdAndAgreementNumber(
        String tenantId, UUID companyId, String agreementNumber
    );
}
