package com.enterprise.mm.repository;

import com.enterprise.mm.entity.SsoSetting;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SsoSettingRepository extends JpaRepository<SsoSetting, UUID> {
    Optional<SsoSetting> findAnyById(UUID id);
    Optional<SsoSetting> findByIdAndIsDeletedFalse(UUID id);
    Optional<SsoSetting> findFirstByTenantIdAndIsDeletedFalseOrderByCreatedAtAsc(String tenantId);
    Optional<SsoSetting> findByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId);
}

