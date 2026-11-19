package com.enterprise.mm.repository;

import com.enterprise.mm.entity.NonMovingStockSavedView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NonMovingStockSavedViewRepository extends JpaRepository<NonMovingStockSavedView, UUID> {
    List<NonMovingStockSavedView> findByTenantIdAndCompanyIdAndOwnerUsernameAndIsDeletedFalseOrderByNameAsc(
        String tenantId, UUID companyId, String ownerUsername
    );

    List<NonMovingStockSavedView> findByTenantIdAndCompanyIdAndOwnerUsernameAndWorkspaceModeAndIsDeletedFalseOrderByNameAsc(
        String tenantId, UUID companyId, String ownerUsername, String workspaceMode
    );

    Optional<NonMovingStockSavedView> findByIdAndTenantIdAndCompanyIdAndOwnerUsernameAndIsDeletedFalse(
        UUID id, String tenantId, UUID companyId, String ownerUsername
    );
}
