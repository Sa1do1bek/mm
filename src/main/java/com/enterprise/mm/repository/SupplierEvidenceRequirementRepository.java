package com.enterprise.mm.repository;

import com.enterprise.mm.entity.SupplierEvidenceRequirement;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierEvidenceRequirementRepository
    extends JpaRepository<SupplierEvidenceRequirement, UUID> {

    List<SupplierEvidenceRequirement>
    findByTenantIdAndCompanyIdAndTemplateVersionIdAndIsDeletedFalseOrderByDisplayOrderAsc(
        String tenantId,
        UUID companyId,
        UUID templateVersionId
    );
}
