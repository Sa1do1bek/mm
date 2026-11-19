package com.enterprise.mm.repository;

import com.enterprise.mm.entity.IntegrationSetting;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationSettingRepository extends JpaRepository<IntegrationSetting, UUID> {
    Optional<IntegrationSetting> findAnyById(UUID id);
    Optional<IntegrationSetting> findByIdAndIsDeletedFalse(UUID id);
    Optional<IntegrationSetting> findByTenantIdAndCompanyIdAndSystemCodeAndIsDeletedFalse(String tenantId, UUID companyId, String systemCode);

    List<IntegrationSetting> findByTenantIdAndCompanyIdAndIsDeletedFalseOrderBySystemCodeAsc(String tenantId, UUID companyId);

    List<IntegrationSetting> findBySystemCodeAndEnabledTrueAndMockModeFalseAndIsDeletedFalse(String systemCode);
}

