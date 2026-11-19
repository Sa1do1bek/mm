package com.enterprise.mm.repository;

import com.enterprise.mm.entity.SupplierAccreditationEvidenceRequirement;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierAccreditationEvidenceRequirementRepository
    extends JpaRepository<SupplierAccreditationEvidenceRequirement, UUID> {

    List<SupplierAccreditationEvidenceRequirement>
    findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByDisplayOrderAsc(
        String tenantId,
        UUID companyId,
        UUID applicationId
    );

    boolean existsByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID applicationId
    );
}
