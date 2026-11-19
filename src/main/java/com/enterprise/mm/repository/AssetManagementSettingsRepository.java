package com.enterprise.mm.repository;

import com.enterprise.mm.entity.AssetManagementSettings;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssetManagementSettingsRepository extends JpaRepository<AssetManagementSettings, UUID> {
    Optional<AssetManagementSettings> findByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId);
    Optional<AssetManagementSettings> findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select settings
        from AssetManagementSettings settings
        where settings.tenantId = :tenantId
          and settings.companyId = :companyId
          and settings.isDeleted = false
        """)
    Optional<AssetManagementSettings> findByTenantIdAndCompanyIdForUpdate(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId
    );
}
