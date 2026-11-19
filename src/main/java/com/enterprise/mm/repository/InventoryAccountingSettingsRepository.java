package com.enterprise.mm.repository;

import com.enterprise.mm.entity.InventoryAccountingSettings;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryAccountingSettingsRepository extends JpaRepository<InventoryAccountingSettings, UUID> {
    Optional<InventoryAccountingSettings> findByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId);
    Optional<InventoryAccountingSettings> findAnyById(UUID id);
    Optional<InventoryAccountingSettings> findByIdAndIsDeletedFalse(UUID id);
}
