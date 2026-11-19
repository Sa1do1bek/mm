package com.enterprise.mm.repository;

import com.enterprise.mm.entity.AssetOpeningImport;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetOpeningImportRepository extends JpaRepository<AssetOpeningImport, UUID> {
    Optional<AssetOpeningImport> findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID id);
}
