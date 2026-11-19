package com.enterprise.mm.repository;

import com.enterprise.mm.entity.AssetNumberRegistry;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetNumberRegistryRepository extends JpaRepository<AssetNumberRegistry, UUID> {
    Optional<AssetNumberRegistry> findByTenantIdAndCompanyIdAndNormalizedNumberAndIsDeletedFalse(
        String tenantId, UUID companyId, String normalizedNumber);
}
