package com.enterprise.mm.repository;

import com.enterprise.mm.entity.SupplierEvidenceTemplateVersion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierEvidenceTemplateVersionRepository
    extends JpaRepository<SupplierEvidenceTemplateVersion, UUID> {

    Optional<SupplierEvidenceTemplateVersion>
    findFirstByTenantIdAndCompanyIdAndAccreditationTypeAndStatusAndIsDeletedFalseOrderByTemplateVersionDesc(
        String tenantId,
        UUID companyId,
        String accreditationType,
        String status
    );
}
