package com.enterprise.mm.repository;

import com.enterprise.mm.entity.SupplierPortalAccount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SupplierPortalAccountRepository extends JpaRepository<SupplierPortalAccount, UUID>, JpaSpecificationExecutor<SupplierPortalAccount> {
    Optional<SupplierPortalAccount> findAnyById(UUID id);
    Optional<SupplierPortalAccount> findByIdAndIsDeletedFalse(UUID id);
    Optional<SupplierPortalAccount> findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalse(String tenantId, UUID companyId, UUID supplierId);
    List<SupplierPortalAccount> findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId,
        UUID companyId,
        UUID supplierId
    );

    Optional<SupplierPortalAccount> findByTenantIdAndCompanyIdAndLoginEmailIgnoreCaseAndIsDeletedFalse(String tenantId, UUID companyId, String loginEmail);
    List<SupplierPortalAccount> findByTenantIdAndCompanyIdAndLoginEmailIgnoreCaseAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId,
        UUID companyId,
        String loginEmail
    );

    Optional<SupplierPortalAccount> findByPortalTokenHashAndIsDeletedFalse(String portalTokenHash);
    List<SupplierPortalAccount> findByPortalTokenHash(String portalTokenHash);
    long countByPortalTokenHash(String portalTokenHash);

    List<SupplierPortalAccount> findByTenantIdAndCompanyIdAndSupplier_IdAndStatusAndIsDeletedFalse(String tenantId, UUID companyId, UUID supplierId, String status);

    long countByTenantIdAndCompanyIdAndSupplier_IdAndStatusAndIsDeletedFalse(String tenantId, UUID companyId, UUID supplierId, String status);

    long countByTenantIdAndCompanyIdAndSupplier_IdAndPortalRoleAndStatusAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID supplierId,
        String portalRole,
        String status
    );
}
